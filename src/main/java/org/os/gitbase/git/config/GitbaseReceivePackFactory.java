package org.os.gitbase.git.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.hook.PushActivityHook;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Write gate for {@code git push} over smart HTTP.
 *
 * <p>JGit calls this factory twice per push — for the {@code info/refs?service=git-receive-pack}
 * advertisement and again for the {@code git-receive-pack} POST — so a denied caller is turned away
 * before any pack data is uploaded. By the time it runs, {@link GitbaseRepositoryResolver} has
 * already opened the repository, so the caller is known to have <b>read</b> access; this adds the
 * <b>write</b> requirement (owner, or a WRITE/ADMIN collaborator).
 *
 * <ul>
 *   <li>anonymous → {@link ServiceNotAuthorizedException} → 401 (git prompts for credentials)</li>
 *   <li>no write access → {@link ServiceNotEnabledException} → 403 with a readable message</li>
 * </ul>
 *
 * <p>Returning a {@link ReceivePack} is also what <i>enables</i> push at all: JGit's default factory
 * refuses receive-pack unless {@code http.receivepack} is set in each repository's config.
 */
@Slf4j
@Component
public class GitbaseReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    private final RepositoryAccessGuard accessGuard;
    private final PushActivityHook pushActivityHook;

    public GitbaseReceivePackFactory(RepositoryAccessGuard accessGuard, PushActivityHook pushActivityHook) {
        this.accessGuard = accessGuard;
        this.pushActivityHook = pushActivityHook;
    }

    @Override
    public ReceivePack create(HttpServletRequest req, Repository db)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        Principal principal = GitRequestPrincipal.of(req);
        if (principal == null) {
            throw new ServiceNotAuthorizedException();
        }

        RepositoryCoordinates repo = RepositoryCoordinates.fromGitDir(db.getDirectory())
                .orElseThrow(() -> new ServiceNotEnabledException("Repository not found"));
        try {
            accessGuard.requireWriteAccess(repo.owner(), repo.repoName(), principal);
        } catch (AccessDeniedDomainException denied) {
            log.info("Push refused: no write access to {}/{}", repo.owner(), repo.repoName());
            throw new ServiceNotEnabledException(denied.getMessage());
        } catch (ResourceNotFoundException gone) {
            // Deleted between resolve and push, or visibility changed — never reveal which.
            throw new ServiceNotEnabledException("Repository not found");
        }

        ReceivePack receivePack = new ReceivePack(db);
        receivePack.setPostReceiveHook(pushActivityHook);
        return receivePack;
    }
}
