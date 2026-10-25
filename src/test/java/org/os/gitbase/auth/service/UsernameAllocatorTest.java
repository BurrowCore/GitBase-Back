package org.os.gitbase.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.repository.UserRepository;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsernameAllocatorTest {

    private final Set<String> taken = new HashSet<>();
    private UsernameAllocator allocator;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsByNameIgnoreCase(anyString()))
                .thenAnswer(inv -> taken.contains(inv.<String>getArgument(0).toLowerCase(Locale.ROOT)));
        allocator = new UsernameAllocator(users);
    }

    @Test
    void usesTheEmailLocalPart_whenFree() {
        assertThat(allocator.allocate("alice@example.com")).isEqualTo("alice");
    }

    @Test
    void sameLocalPartOnAnotherDomain_getsADistinctName() {
        // The original bug: alice@evil.com became "alice" and inherited alice's repositories.
        taken.add("alice");
        assertThat(allocator.allocate("alice@evil.com")).isEqualTo("alice-2");

        taken.add("alice-2");
        assertThat(allocator.allocate("Alice@another.org")).isEqualTo("alice-3");
    }

    @Test
    void reservedRouteNames_areNeverHandedOut() {
        assertThat(allocator.allocate("api@example.com")).isEqualTo("api-2");
        assertThat(allocator.allocate("settings@example.com")).isEqualTo("settings-2");
    }

    @Test
    void sanitizesToAPathSafeLowercaseName() {
        assertThat(UsernameAllocator.sanitize("John Smith")).isEqualTo("john-smith");
        assertThat(UsernameAllocator.sanitize("..weird//name..@x.io")).isEqualTo("weird-name");
        assertThat(UsernameAllocator.sanitize("@nolocal.io")).isEqualTo("user");
        assertThat(UsernameAllocator.sanitize("a".repeat(80))).hasSize(UsernameAllocator.MAX_LENGTH);
    }

    @Test
    void suffixedNamesStayWithinTheLengthLimit() {
        String longLocal = "x".repeat(60);
        taken.add("x".repeat(UsernameAllocator.MAX_LENGTH));
        String name = allocator.allocate(longLocal + "@example.com");
        assertThat(name).hasSizeLessThanOrEqualTo(UsernameAllocator.MAX_LENGTH).endsWith("-2");
    }
}
