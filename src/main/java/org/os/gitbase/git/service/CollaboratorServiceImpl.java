package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.CollaboratorDto;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.mapper.CollaboratorMapper;
import org.os.gitbase.git.repository.CollaboratorRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class CollaboratorServiceImpl implements CollaboratorService {

    private final CollaboratorRepository collaboratorRepository;
    private final GitRepositoryDB repositoryDB;
    private final UserRepository userRepository;
    private final ActivityService activityService;
    private final CollaboratorMapper collaboratorMapper;

    public CollaboratorServiceImpl(CollaboratorRepository collaboratorRepository,
                                   GitRepositoryDB repositoryDB,
                                   UserRepository userRepository,
                                   ActivityService activityService,
                                   CollaboratorMapper collaboratorMapper) {
        this.collaboratorMapper = collaboratorMapper;
        this.collaboratorRepository = collaboratorRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.activityService = activityService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CollaboratorDto> list(String owner, String repoName) {
        RepositoryGit repo = requireRepository(owner, repoName);
        return collaboratorMapper.toDtoList(collaboratorRepository.findByRepository(repo));
    }

    @Override
    @Transactional
    public CollaboratorDto set(String owner, String repoName, String username, String permission) {
        RepositoryGit repo = requireRepository(owner, repoName);
        RepositoryPermission level = parsePermission(permission);

        String target = username == null ? "" : username.trim();
        // The owner already outranks every grant; storing one would be meaningless and would let
        // them accidentally "demote" themselves in the UI.
        if (target.equalsIgnoreCase(owner)) {
            throw new ConflictException("The repository owner already has full access");
        }

        User user = userRepository.findUserByName(target)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + target));

        // Upsert: adding an existing collaborator updates their level rather than failing.
        Collaborator grant = collaboratorRepository.findByRepositoryAndUser(repo, user)
                .orElseGet(() -> {
                    Collaborator fresh = new Collaborator();
                    fresh.setRepository(repo);
                    fresh.setUser(user);
                    return fresh;
                });
        grant.setPermission(level);
        Collaborator saved = collaboratorRepository.save(grant);

        activityService.logActivity(ActivityType.REPO_UPDATED, owner, repoName,
                "[ " + repoName + " ] " + target + " granted " + level + " access");

        return collaboratorMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void remove(String owner, String repoName, String username) {
        RepositoryGit repo = requireRepository(owner, repoName);
        String target = username == null ? "" : username.trim();

        User user = userRepository.findUserByName(target)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + target));

        Collaborator grant = collaboratorRepository.findByRepositoryAndUser(repo, user)
                .orElseThrow(() -> new ResourceNotFoundException(
                        target + " is not a collaborator on " + owner + "/" + repoName));

        collaboratorRepository.delete(grant);

        // Their existing issues and pull requests are deliberately left untouched (as Gitea does) —
        // removing access should not rewrite history they authored.
        activityService.logActivity(ActivityType.REPO_UPDATED, owner, repoName,
                "[ " + repoName + " ] " + target + "'s access was revoked");
    }

    private RepositoryPermission parsePermission(String permission) {
        try {
            return RepositoryPermission.valueOf(permission.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Permission must be READ, WRITE or ADMIN (got '" + permission + "')");
        }
    }

    private RepositoryGit requireRepository(String owner, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(owner, repoName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + owner + "/" + repoName));
    }

}
