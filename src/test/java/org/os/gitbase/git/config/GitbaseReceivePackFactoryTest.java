package org.os.gitbase.git.config;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.hook.PushActivityHook;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.File;
import java.nio.file.Path;
import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write gate for {@code git push}: before this existed, any authenticated user could push to
 * someone else's public repository, and a READ collaborator could push to a private one.
 */
@ExtendWith(MockitoExtension.class)
class GitbaseReceivePackFactoryTest {

    @Mock
    private RepositoryAccessGuard accessGuard;

    @Mock
    private PushActivityHook pushActivityHook;

    @Mock
    private HttpServletRequest request;

    @TempDir
    Path tempDir;

    private Repository repository;
    private GitbaseReceivePackFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        File gitDir = tempDir.resolve("alice").resolve("project.git").toFile();
        repository = new FileRepositoryBuilder().setGitDir(gitDir).build();
        repository.create(true);
        factory = new GitbaseReceivePackFactory(accessGuard, pushActivityHook);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    private Principal caller(String name) {
        Principal principal = () -> name;
        when(request.getUserPrincipal()).thenReturn(principal);
        return principal;
    }

    @Test
    void writer_getsAReceivePackWithTheActivityHook() throws Exception {
        Principal bob = caller("bob");

        ReceivePack receivePack = factory.create(request, repository);

        assertThat(receivePack).isNotNull();
        assertThat(receivePack.getPostReceiveHook()).isSameAs(pushActivityHook);
        verify(accessGuard).requireWriteAccess("alice", "project", bob);
    }

    @Test
    void readOnlyCaller_isRefusedWith403() {
        Principal bob = caller("bob");
        when(accessGuard.requireWriteAccess("alice", "project", bob))
                .thenThrow(new AccessDeniedDomainException("You do not have write access to alice/project"));

        assertThatThrownBy(() -> factory.create(request, repository))
                .isInstanceOf(ServiceNotEnabledException.class)
                .hasMessageContaining("write access");
    }

    @Test
    void invisibleRepository_isRefusedWithoutRevealingIt() {
        caller("mallory");
        when(accessGuard.requireWriteAccess(eq("alice"), eq("project"), any()))
                .thenThrow(new ResourceNotFoundException("Repository not found: alice/project"));

        assertThatThrownBy(() -> factory.create(request, repository))
                .isInstanceOf(ServiceNotEnabledException.class)
                .hasMessage("Repository not found");
    }

    @Test
    void anonymousCaller_isAskedToAuthenticate() {
        when(request.getUserPrincipal()).thenReturn(null);

        assertThatThrownBy(() -> factory.create(request, repository))
                .isInstanceOf(ServiceNotAuthorizedException.class);
        verify(accessGuard, never()).requireWriteAccess(any(), any(), any());
    }
}
