package org.os.gitbase.security;

import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.helper.Helper;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Central authorization gate for repository access.
 *
 * <p>Visibility model (mirrors GitHub / Gitea):
 * <ul>
 *   <li><b>Public</b> repositories are readable by any authenticated user.</li>
 *   <li><b>Private</b> repositories are readable only by their owner.</li>
 * </ul>
 *
 * <p>When a caller is denied a private repository we throw {@link ResourceNotFoundException}
 * (HTTP 404) rather than 403, so a non-owner cannot distinguish "private repo I can't see"
 * from "repo that doesn't exist" — this prevents private-repository enumeration.
 *
 * <p>Read is a single cheap DB lookup ({@code findByOwnerNameAndRepoName}); it does no work
 * proportional to repository count or history, so it is safe on every read request (ADR-004).
 */
@Component
public class RepositoryAccessGuard {

    private final GitRepositoryDB repositoryDB;

    public RepositoryAccessGuard(GitRepositoryDB repositoryDB) {
        this.repositoryDB = repositoryDB;
    }

    /**
     * Ensures the authenticated caller may read the given repository.
     *
     * @return the resolved repository entity (handy for callers that also need its metadata)
     * @throws ResourceNotFoundException if the repository does not exist, or is private and the
     *                                   caller is not its owner (masked as "not found")
     */
    public RepositoryGit requireReadAccess(String ownerUsername, String repoName, Principal principal) {
        RepositoryGit repo = repositoryDB.findByOwnerNameAndRepoName(ownerUsername, repoName)
                .orElseThrow(() -> notFound(ownerUsername, repoName));

        if (!repo.isPrivate() || isOwner(ownerUsername, principal)) {
            return repo;
        }
        // Private repo the caller does not own: mask its existence.
        throw notFound(ownerUsername, repoName);
    }

    /** True when the authenticated caller owns the given namespace. */
    public boolean isOwner(String ownerUsername, Principal principal) {
        if (principal == null || ownerUsername == null) {
            return false;
        }
        String caller = Helper.removeAtSymbolAndFollowing(principal.getName());
        return caller != null && caller.equals(ownerUsername);
    }

    private ResourceNotFoundException notFound(String ownerUsername, String repoName) {
        return new ResourceNotFoundException("Repository not found: " + ownerUsername + "/" + repoName);
    }
}
