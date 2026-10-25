package org.os.gitbase.security;

import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.Team;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.CollaboratorRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.TeamGrant;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.repository.TeamRepoRepository;
import org.os.gitbase.git.repository.TeamRepository;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Central authorization gate for repository access.
 *
 * <p><b>Organizations</b> (DECISION-014): for a repository owned by an organization, members of
 * its Owners team count as owners; other members get the strongest permission of their teams that
 * cover the repository, combined with any direct collaborator grant by taking the stronger.
 *
 * <p><b>Effective permission</b> is resolved in this order — the first match wins:
 * <ol>
 *   <li><b>Owner</b> → outranks everything; the only role that may delete the repository or
 *       manage its collaborators.</li>
 *   <li><b>Collaborator</b> → the level on their {@link Collaborator} grant (READ/WRITE/ADMIN).</li>
 *   <li><b>Public visibility</b> → any authenticated user gets READ.</li>
 *   <li>Otherwise → no access.</li>
 * </ol>
 *
 * <p>When a caller is denied a <b>private</b> repository we throw {@link ResourceNotFoundException}
 * (HTTP 404) rather than 403, so a non-collaborator cannot distinguish "private repo I can't see"
 * from "repo that doesn't exist". This prevents private-repository enumeration. Once a repository
 * is visible to the caller, further denials are honest 403s.
 *
 * <p>Cost is one indexed lookup per check, with no work proportional to repository count or
 * history — safe on every request (ADR-004).
 */
@Component
public class RepositoryAccessGuard {

    private final GitRepositoryDB repositoryDB;
    private final CollaboratorRepository collaboratorRepository;
    private final CurrentUserResolver currentUserResolver;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepoRepository teamRepoRepository;

    public RepositoryAccessGuard(GitRepositoryDB repositoryDB, CollaboratorRepository collaboratorRepository,
                                 CurrentUserResolver currentUserResolver, TeamRepository teamRepository,
                                 TeamMemberRepository teamMemberRepository, TeamRepoRepository teamRepoRepository) {
        this.repositoryDB = repositoryDB;
        this.collaboratorRepository = collaboratorRepository;
        this.currentUserResolver = currentUserResolver;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepoRepository = teamRepoRepository;
    }

    // ─────────────────────────── read ───────────────────────────

    /**
     * Ensures the caller may read the repository.
     *
     * @throws ResourceNotFoundException if it does not exist, or is private and the caller has no
     *                                   grant on it (masked as "not found")
     */
    public RepositoryGit requireReadAccess(String ownerUsername, String repoName, Principal principal) {
        RepositoryGit repo = resolve(ownerUsername, repoName);
        if (canRead(repo, ownerUsername, principal)) {
            return repo;
        }
        throw notFound(ownerUsername, repoName);
    }

    // ─────────────────────────── write ───────────────────────────

    /**
     * Ensures the caller may write (push, merge, manage branches).
     *
     * <p>A private repository the caller cannot even see is masked as 404; a repository they can
     * see but lack WRITE on yields an honest 403.
     */
    public RepositoryGit requireWriteAccess(String ownerUsername, String repoName, Principal principal) {
        RepositoryGit repo = requireReadAccess(ownerUsername, repoName, principal);
        if (!hasAtLeast(repo, ownerUsername, principal, RepositoryPermission.WRITE)) {
            throw new AccessDeniedDomainException(
                    "You do not have write access to " + ownerUsername + "/" + repoName);
        }
        return repo;
    }

    /** Ensures the caller may change repository settings (ADMIN or owner). */
    public RepositoryGit requireAdminAccess(String ownerUsername, String repoName, Principal principal) {
        RepositoryGit repo = requireReadAccess(ownerUsername, repoName, principal);
        if (!hasAtLeast(repo, ownerUsername, principal, RepositoryPermission.ADMIN)) {
            throw new AccessDeniedDomainException(
                    "You do not have admin access to " + ownerUsername + "/" + repoName);
        }
        return repo;
    }

    /**
     * Ensures the caller is the repository owner. Reserved for the two operations no collaborator
     * may perform however privileged: deleting the repository and managing its collaborators.
     */
    public RepositoryGit requireOwner(String ownerUsername, String repoName, Principal principal) {
        RepositoryGit repo = requireReadAccess(ownerUsername, repoName, principal);
        if (!isOwner(ownerUsername, principal)) {
            throw new AccessDeniedDomainException(
                    "Only the owner of " + ownerUsername + "/" + repoName + " can perform this action");
        }
        return repo;
    }

    // ─────────────────────────── queries ───────────────────────────

    /**
     * True when the caller owns the namespace: it is their own account, or it is an organization
     * and they are in its Owners team. Personal namespaces have no teams, so the second check is
     * simply false for them.
     */
    public boolean isOwner(String ownerUsername, Principal principal) {
        String caller = callerName(principal);
        if (caller == null || ownerUsername == null) {
            return false;
        }
        return caller.equals(ownerUsername) || teamMemberRepository.isOwner(ownerUsername, caller);
    }

    /**
     * Effective permission for the caller, or empty when they have no access at all.
     * Useful for telling the UI which controls to render.
     */
    public Optional<RepositoryPermission> effectivePermission(String ownerUsername, String repoName,
                                                              Principal principal) {
        return repositoryDB.findByOwnerNameAndRepoName(ownerUsername, repoName)
                .flatMap(repo -> effectivePermission(repo, ownerUsername, principal));
    }

    private Optional<RepositoryPermission> effectivePermission(RepositoryGit repo, String ownerUsername,
                                                               Principal principal) {
        String caller = callerName(principal);
        if (caller == null) {
            return Optional.empty();
        }
        // 1. The account owner outranks every grant.
        if (caller.equals(ownerUsername)) {
            return Optional.of(RepositoryPermission.ADMIN);
        }
        // 2. Organization teams: the Owners team is owner-level; otherwise the
        //    strongest team that covers this repository. Empty for personal namespaces.
        RepositoryPermission best = null;
        for (TeamGrant grant : teamRepository.findGrants(ownerUsername, caller, repo.getId())) {
            if (grant.ownerTeam()) {
                return Optional.of(RepositoryPermission.ADMIN);
            }
            best = stronger(best, grant.permission());
        }
        // 3. A direct collaborator grant — combined with team access by taking the stronger.
        Optional<Collaborator> collaborator = collaboratorRepository.findByRepositoryAndUsername(repo, caller);
        if (collaborator.isPresent()) {
            best = stronger(best, collaborator.get().getPermission());
        }
        if (best != null) {
            return Optional.of(best);
        }
        // 4. Public repositories are readable by any authenticated user.
        return repo.isPrivate() ? Optional.empty() : Optional.of(RepositoryPermission.READ);
    }

    private boolean canRead(RepositoryGit repo, String ownerUsername, Principal principal) {
        return effectivePermission(repo, ownerUsername, principal).isPresent();
    }

    private boolean hasAtLeast(RepositoryGit repo, String ownerUsername, Principal principal,
                               RepositoryPermission required) {
        return effectivePermission(repo, ownerUsername, principal)
                .filter(permission -> permission.atLeast(required))
                .isPresent();
    }

    private static RepositoryPermission stronger(RepositoryPermission current, RepositoryPermission candidate) {
        return current == null || candidate.atLeast(current) ? candidate : current;
    }

    /**
     * Which private repositories in {@code ownerUsername}'s namespace the caller may see, for
     * listings: {@link PrivateRepoVisibility#all()} for its owners and members of a team that
     * covers every repository, otherwise the named ones (collaborator grants plus team grants).
     * A fixed number of queries, never a check per repository.
     */
    public PrivateRepoVisibility privateRepoVisibility(String ownerUsername, Principal principal) {
        String caller = callerName(principal);
        if (caller == null) {
            return PrivateRepoVisibility.none();
        }
        if (isOwner(ownerUsername, principal)) {
            return PrivateRepoVisibility.all();
        }
        List<Team> teams = teamRepository.findTeamsOfMember(ownerUsername, caller);
        if (teams.stream().anyMatch(team -> team.isOwnerTeam() || team.isIncludesAllRepositories())) {
            return PrivateRepoVisibility.all();
        }
        Set<String> names = new HashSet<>(collaboratorRepository.findCollaboratingRepoNames(ownerUsername, caller));
        if (!teams.isEmpty()) {
            names.addAll(teamRepoRepository.findRepoNamesOfTeams(teams));
        }
        return PrivateRepoVisibility.only(names);
    }

    /** Result of {@link #privateRepoVisibility}. */
    public record PrivateRepoVisibility(boolean allVisible, Set<String> names) {
        static PrivateRepoVisibility all() {
            return new PrivateRepoVisibility(true, Set.of());
        }

        static PrivateRepoVisibility none() {
            return new PrivateRepoVisibility(false, Set.of());
        }

        static PrivateRepoVisibility only(Set<String> names) {
            return new PrivateRepoVisibility(false, Set.copyOf(names));
        }

        public boolean canSee(String repoName) {
            return allVisible || names.contains(repoName);
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private RepositoryGit resolve(String ownerUsername, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(ownerUsername, repoName)
                .orElseThrow(() -> notFound(ownerUsername, repoName));
    }

    /**
     * Caller's username — the stored name of the account the principal authenticated as. Never
     * the email's local part: that let {@code alice@evil.com} act as user {@code alice}.
     */
    private String callerName(Principal principal) {
        return currentUserResolver.usernameOrNull(principal);
    }

    private ResourceNotFoundException notFound(String ownerUsername, String repoName) {
        return new ResourceNotFoundException("Repository not found: " + ownerUsername + "/" + repoName);
    }
}
