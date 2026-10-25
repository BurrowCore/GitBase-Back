package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.TransferRepositoryResultDto;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.CollaboratorRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.repository.TeamRepoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Moves a repository into an organization ("transfer ownership").
 *
 * <p>Allowed when the caller owns the repository and may create repositories in the target
 * organization — so the transfer is immediate, with no acceptance step. Issues, pull requests,
 * labels, collaborators and history move with it: they reference the repository row, which is
 * kept and only changes owner. The bare repository moves on disk from {@code {old}/{repo}.git}
 * to {@code {org}/{repo}.git}; old clone URLs stop working (no redirects yet).
 *
 * <p>Database and disk change together: the folder is moved inside the transaction, so a failed
 * move rolls the owner change back, and if the commit itself fails afterwards the folder is moved
 * back. The repository row is locked for the duration, so two transfers cannot race.
 */
@Slf4j
@Service
public class RepositoryTransferService {

    private final GitRepositoryDB repositoryDB;
    private final UserRepository userRepository;
    private final TeamMemberRepository memberRepository;
    private final OrganizationService organizationService;
    private final CollaboratorRepository collaboratorRepository;
    private final ActivityService activityService;
    private final TeamRepoRepository teamRepoRepository;
    private final String basePath;

    public RepositoryTransferService(GitRepositoryDB repositoryDB, UserRepository userRepository,
                                     TeamMemberRepository memberRepository, OrganizationService organizationService,
                                     CollaboratorRepository collaboratorRepository, ActivityService activityService,
                                     TeamRepoRepository teamRepoRepository,
                                     @Value("${gitbase.repositories-path:./gitbase/repositories}") String basePath) {
        this.teamRepoRepository = teamRepoRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.organizationService = organizationService;
        this.collaboratorRepository = collaboratorRepository;
        this.activityService = activityService;
        this.basePath = basePath;
    }

    /**
     * @param caller the acting user — the controller has already checked they own the repository
     * @throws AccessDeniedDomainException if they may not create repositories in {@code newOwner} (403)
     * @throws ConflictException           if {@code newOwner} already has a repository of that name (409)
     */
    @Transactional
    public TransferRepositoryResultDto transfer(String owner, String repoName, String newOwner, String caller) {
        String target = newOwner == null ? "" : newOwner.trim().toLowerCase(java.util.Locale.ROOT);
        if (target.equals(owner)) {
            throw new IllegalArgumentException(repoName + " already belongs to " + owner);
        }
        User org = userRepository.findUserByName(target)
                .filter(User::isOrganization)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + target));
        if (!organizationService.canCreateRepository(target, caller)) {
            throw new AccessDeniedDomainException("You can only transfer into an organization where you may create repositories");
        }
        RepositoryGit found = repositoryDB.findByOwnerNameAndRepoName(owner, repoName)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + owner + "/" + repoName));
        RepositoryGit repo = repositoryDB.findByIdForUpdate(found.getId()).orElseThrow();
        if (repositoryDB.findByOwnerNameAndRepoName(target, repoName).isPresent()) {
            throw new ConflictException(target + " already has a repository named " + repoName);
        }

        repo.setOwner(org);
        repositoryDB.save(repo);
        // Grants that belonged to the previous owner's organization (if it was one) no longer apply.
        teamRepoRepository.deleteByRepository(repo);
        // Owners of the new organization already have full access; a separate grant would be noise.
        collaboratorRepository.deleteOrganizationOwnersOf(repo, org);
        keepCallerAccess(repo, target, caller);
        moveOnDisk(owner, target, repoName);

        activityService.logActivity(ActivityType.REPO_UPDATED, caller, repoName,
                "[ " + repoName + " ] Transferred from " + owner + " to " + target);
        log.info("Repository {}/{} transferred to {} by {}", owner, repoName, target, caller);
        return new TransferRepositoryResultDto(target, repoName,
                "/gitbase/" + target + "/" + repoName + ".git");
    }

    /**
     * Someone who may create in the organization but does not own it would lose the repository they
     * just handed over if no team covers it — so, as when creating a repository there, they keep
     * ADMIN through a collaborator grant. Organization owners already have full access.
     */
    private void keepCallerAccess(RepositoryGit repo, String org, String caller) {
        if (memberRepository.isOwner(org, caller)) {
            return;
        }
        User user = userRepository.findUserByName(caller).orElseThrow();
        Collaborator grant = collaboratorRepository.findByRepositoryAndUser(repo, user).orElseGet(() -> {
            Collaborator fresh = new Collaborator();
            fresh.setRepository(repo);
            fresh.setUser(user);
            return fresh;
        });
        grant.setPermission(RepositoryPermission.ADMIN);
        collaboratorRepository.save(grant);
    }

    private void moveOnDisk(String from, String to, String repoName) {
        Path source = Paths.get(basePath, from, repoName + ".git");
        Path destination = Paths.get(basePath, to, repoName + ".git");
        if (Files.exists(destination)) {
            throw new ConflictException("A repository folder already exists at " + to + "/" + repoName);
        }
        try {
            Files.createDirectories(destination.getParent());
            move(source, destination);
        } catch (IOException e) {
            throw new IllegalStateException("Could not move the repository on disk", e);
        }
        // If the database commit fails after this point, put the folder back where the row says it is.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            move(destination, source);
                        } catch (IOException e) {
                            log.error("Transfer of {} rolled back but the folder could not be moved back from {}",
                                    repoName, destination, e);
                        }
                    }
                }
            });
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }
}
