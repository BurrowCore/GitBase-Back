package org.os.gitbase.security;

import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.CollaboratorRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.helper.Helper;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.Set;

/**
 * Central authorization gate for repository access.
 *
 * <p><b>Effective permission</b> is resolved in Gitea's order — the first match wins:
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

    public RepositoryAccessGuard(GitRepositoryDB repositoryDB, CollaboratorRepository collaboratorRepository) {
        this.repositoryDB = repositoryDB;
        this.collaboratorRepository = collaboratorRepository;
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

    /** True when the authenticated caller owns the given namespace. */
    public boolean isOwner(String ownerUsername, Principal principal) {
        String caller = callerName(principal);
        return caller != null && ownerUsername != null && caller.equals(ownerUsername);
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
        // 1. Owner outranks every grant.
        if (isOwner(ownerUsername, principal)) {
            return Optional.of(RepositoryPermission.ADMIN);
        }
        // 2. An explicit collaborator grant.
        String caller = callerName(principal);
        if (caller != null) {
            Optional<Collaborator> grant = collaboratorRepository.findByRepositoryAndUsername(repo, caller);
            if (grant.isPresent()) {
                return Optional.of(grant.get().getPermission());
            }
        }
        // 3. Public repositories are readable by any authenticated user.
        if (!repo.isPrivate() && caller != null) {
            return Optional.of(RepositoryPermission.READ);
        }
        return Optional.empty();
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

    /**
     * Repository names in {@code ownerUsername}'s namespace that the caller collaborates on.
     * Lets a listing include private repos the caller may see without a per-row permission check.
     */
    public Set<String> collaboratingRepoNames(String ownerUsername, Principal principal) {
        String caller = callerName(principal);
        if (caller == null) {
            return Set.of();
        }
        return Set.copyOf(collaboratorRepository.findCollaboratingRepoNames(ownerUsername, caller));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private RepositoryGit resolve(String ownerUsername, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(ownerUsername, repoName)
                .orElseThrow(() -> notFound(ownerUsername, repoName));
    }

    /** Caller's username, normalised the same way the rest of the app does (email → local part). */
    private String callerName(Principal principal) {
        return principal == null ? null : Helper.removeAtSymbolAndFollowing(principal.getName());
    }

    private ResourceNotFoundException notFound(String ownerUsername, String repoName) {
        return new ResourceNotFoundException("Repository not found: " + ownerUsername + "/" + repoName);
    }
}
