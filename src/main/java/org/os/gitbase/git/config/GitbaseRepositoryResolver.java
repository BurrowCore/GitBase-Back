package org.os.gitbase.git.config;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.security.Principal;

/**
 * Resolves a bare repository for the JGit {@code GitServlet} (the {@code /gitbase/*} smart-HTTP
 * endpoint), enforcing two things the default resolver does not:
 *
 * <ol>
 *   <li><b>Path jail</b> — the requested {@code owner/repo.git} name is rejected if it could
 *       escape the repositories root (path-traversal), before any filesystem access.</li>
 *   <li><b>Visibility</b> — public repos are readable by any authenticated user; private repos
 *       only by their owner. A denied/absent repo is masked as "not found" so private
 *       repositories cannot be enumerated over git.</li>
 * </ol>
 *
 * <p>This closes the git-CLI clone/pull path: without it, any user holding a valid git token
 * could clone anyone else's private repository.
 */
@Component
public class GitbaseRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    private static final String BASE_PATH = "./gitbase/repositories";

    private final RepositoryAccessGuard accessGuard;

    public GitbaseRepositoryResolver(RepositoryAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    @Override
    public Repository open(HttpServletRequest req, String name) throws RepositoryNotFoundException {
        String normalized = name.replace('\\', '/');

        // 1. Jail: refuse anything that could climb out of the repositories root.
        if (normalized.contains("..") || normalized.startsWith("/")) {
            throw new RepositoryNotFoundException(name);
        }

        // 2. Parse "owner/repo.git" → owner + repoName (single-level namespace).
        String path = normalized.endsWith(".git")
                ? normalized.substring(0, normalized.length() - 4)
                : normalized;
        int slash = path.indexOf('/');
        if (slash <= 0 || slash >= path.length() - 1) {
            throw new RepositoryNotFoundException(name);
        }
        String owner = path.substring(0, slash);
        String repoName = path.substring(slash + 1);
        if (repoName.contains("/")) {
            throw new RepositoryNotFoundException(name);
        }

        // 3. Visibility gate. Denied/absent are both surfaced as "not found" (404) so a
        //    non-owner cannot tell a hidden private repo from one that does not exist.
        try {
            accessGuard.requireReadAccess(owner, repoName, resolvePrincipal(req));
        } catch (RuntimeException denied) {
            throw new RepositoryNotFoundException(name, denied);
        }

        // 4. Open the bare repository on disk.
        File repoDir = new File(BASE_PATH, normalized);
        if (!repoDir.exists()) {
            throw new RepositoryNotFoundException(name);
        }
        try {
            return new FileRepositoryBuilder().setGitDir(repoDir).build();
        } catch (IOException e) {
            throw new RepositoryNotFoundException(name, e);
        }
    }

    /** The authenticated caller — from the request, falling back to the security context. */
    private Principal resolvePrincipal(HttpServletRequest req) {
        Principal principal = req.getUserPrincipal();
        if (principal != null) {
            return principal;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth : null;
    }
}
