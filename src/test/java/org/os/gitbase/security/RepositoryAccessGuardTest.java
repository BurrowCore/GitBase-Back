package org.os.gitbase.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.GitRepositoryDB;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Verifies the repository visibility gate that protects every read/write door
 * (REST endpoints, the JGit servlet, and the smart-HTTP command controller).
 */
@ExtendWith(MockitoExtension.class)
class RepositoryAccessGuardTest {

    @Mock
    private GitRepositoryDB repositoryDB;

    @InjectMocks
    private RepositoryAccessGuard guard;

    /** A principal whose name is exactly the value given (mirrors a git-CLI basic-auth username). */
    private static Principal principal(String name) {
        return () -> name;
    }

    private void existing(String owner, String repo, boolean isPrivate) {
        RepositoryGit entity = new RepositoryGit();
        entity.setRepoName(repo);
        entity.setPrivate(isPrivate);
        when(repositoryDB.findByOwnerNameAndRepoName(owner, repo)).thenReturn(Optional.of(entity));
    }

    // ---------- READ ----------

    @Test
    void publicRepo_isReadableByAnyAuthenticatedUser() {
        existing("alice", "repo", false);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("bob")))
                .doesNotThrowAnyException();
    }

    @Test
    void privateRepo_isReadableByOwner() {
        existing("alice", "repo", true);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
    }

    @Test
    void privateRepo_ownerViaOAuthEmailPrincipal_isReadable() {
        // OAuth/JWT principals carry the email; the guard strips the domain to the namespace.
        existing("alice", "repo", true);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("alice@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void privateRepo_isHiddenFromNonOwner_asNotFound() {
        // Masked as 404 (not 403) so private repos cannot be enumerated over git or REST.
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", principal("bob")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void privateRepo_isHiddenFromAnonymous_asNotFound() {
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingRepo_isNotFound() {
        when(repositoryDB.findByOwnerNameAndRepoName("alice", "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "ghost", principal("alice")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- WRITE ----------

    @Test
    void write_allowedForOwner() {
        existing("alice", "repo", false);
        assertThatCode(() -> guard.requireWriteAccess("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
    }

    @Test
    void write_toPublicRepoByNonOwner_isForbidden() {
        // Existence is already public, so a 403 (not a masking 404) is correct here.
        existing("alice", "repo", false);
        assertThatThrownBy(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void write_toPrivateRepoByNonOwner_isMaskedAsNotFound() {
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
