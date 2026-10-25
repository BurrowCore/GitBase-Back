package org.os.gitbase.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserResolverTest {

    private UserRepository users;
    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.findUserByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findUserByName(anyString())).thenReturn(Optional.empty());
        when(users.countByNameIgnoreCase(anyString())).thenReturn(1L);
        resolver = new CurrentUserResolver(users);
    }

    private static User account(String name) {
        User user = new User();
        user.setName(name);
        return user;
    }

    @Test
    void emailPrincipal_resolvesToTheAccountsStoredUsername() {
        when(users.findUserByEmail("alice@evil.com")).thenReturn(Optional.of(account("alice-2")));
        Principal browser = () -> "alice@evil.com";

        assertThat(resolver.requireUsername(browser)).isEqualTo("alice-2");
    }

    @Test
    void usernamePrincipal_resolvesByName() {
        when(users.findUserByName("alice")).thenReturn(Optional.of(account("alice")));
        Principal gitClient = () -> "alice";

        assertThat(resolver.requireUsername(gitClient)).isEqualTo("alice");
    }

    @Test
    void legacyDuplicateUsername_failsClosedForBothAccounts() {
        // Accounts created before usernames were unique: both stored as "alice".
        when(users.countByNameIgnoreCase("alice")).thenReturn(2L);
        when(users.findUserByEmail("alice@evil.com")).thenReturn(Optional.of(account("alice")));

        assertThat(resolver.usernameOrNull(() -> "alice@evil.com")).isNull();
        assertThat(resolver.usernameOrNull(() -> "alice")).isNull(); // git-CLI path, too
    }

    @Test
    void unknownOrMissingPrincipal_hasNoUsername() {
        assertThat(resolver.usernameOrNull(null)).isNull();
        assertThat(resolver.usernameOrNull(() -> "nobody@nowhere.io")).isNull();
        assertThatThrownBy(() -> resolver.requireUsername(() -> "nobody"))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
