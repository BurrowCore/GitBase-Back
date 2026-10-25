package org.os.gitbase.git.config;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.security.RepositoryAccessGuard;

import java.nio.file.Path;
import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The read gate for clone/fetch: path jail and private-repository masking. */
class GitbaseRepositoryResolverTest {

    @TempDir
    Path tempDir;

    private RepositoryAccessGuard guard;
    private HttpServletRequest request;
    private GitbaseRepositoryResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        guard = mock(RepositoryAccessGuard.class);
        request = mock(HttpServletRequest.class);
        Principal bob = () -> "bob";
        when(request.getUserPrincipal()).thenReturn(bob);
        resolver = new GitbaseRepositoryResolver(guard, tempDir.toString());

        try (Repository repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                .setGitDir(tempDir.resolve("alice/project.git").toFile()).build()) {
            repo.create(true);
        }
    }

    @Test
    void opensAReadableRepository() throws Exception {
        try (Repository repo = resolver.open(request, "alice/project.git")) {
            assertThat(repo.getDirectory().getName()).isEqualTo("project.git");
        }
    }

    @Test
    void nameWithoutGitSuffix_isAccessCheckedThenNotFoundOnDisk() {
        // "alice/project" is checked against the guard as alice/project, but the directory lookup
        // is literal, so without ".git" nothing is opened.
        assertThatThrownBy(() -> resolver.open(request, "alice/project"))
                .isInstanceOf(RepositoryNotFoundException.class);
        verify(guard).requireReadAccess(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../alice/project.git", "alice/../../etc.git", "/alice/project.git",
            "alice\\..\\..\\etc.git", "alice/nested/project.git", "project.git", "alice/"})
    void rejectsPathsThatCouldEscapeTheJail_beforeAnyAccessCheck(String name) {
        assertThatThrownBy(() -> resolver.open(request, name))
                .isInstanceOf(RepositoryNotFoundException.class);
        verify(guard, never()).requireReadAccess(anyString(), anyString(), any());
    }

    @Test
    void hiddenPrivateRepository_looksExactlyLikeAMissingOne() {
        when(guard.requireReadAccess("alice", "project", request.getUserPrincipal()))
                .thenThrow(new ResourceNotFoundException("Repository not found: alice/project"));

        assertThatThrownBy(() -> resolver.open(request, "alice/project.git"))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> resolver.open(request, "alice/does-not-exist.git"))
                .isInstanceOf(RepositoryNotFoundException.class);
    }
}
