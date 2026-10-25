package org.os.gitbase.auth.service;

import org.os.gitbase.auth.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a unique username for a new account.
 *
 * <p>The username is the repository namespace ({@code /{username}/{repo}}) and the identity used
 * by every permission check, so it must be unique and must never change on its own. Gitbase enforces
 * this with a case-insensitive unique {@code lower_name}; we derive a lowercase name from the
 * email's local part and append {@code -2}, {@code -3}, … when it is taken, so
 * {@code alice@evil.com} becomes {@code alice-2} instead of colliding with {@code alice}.
 */
@Component
public class UsernameAllocator {

    /** GitHub's username length limit. */
    static final int MAX_LENGTH = 39;

    private static final int MAX_SEQUENTIAL_ATTEMPTS = 50;

    /** Names that would collide with top-level routes. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "gitbase", "new", "settings", "projects", "login", "logout", "register",
            "oauth2", "explore", "orgs", "org", "user", "users", "assets", "static", "help");

    private final UserRepository userRepository;

    public UsernameAllocator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** A free username derived from {@code seed} (typically an email address or display name). */
    public String allocate(String seed) {
        String base = sanitize(seed);
        if (isFree(base)) {
            return base;
        }
        for (int suffix = 2; suffix < MAX_SEQUENTIAL_ATTEMPTS + 2; suffix++) {
            String candidate = withSuffix(base, "-" + suffix);
            if (isFree(candidate)) {
                return candidate;
            }
        }
        // Pathologically popular name: fall back to a random suffix rather than looping forever.
        String candidate;
        do {
            candidate = withSuffix(base, "-" + Integer.toString(ThreadLocalRandom.current().nextInt(1 << 20), 36));
        } while (!isFree(candidate));
        return candidate;
    }

    /**
     * Lowercase letters, digits, {@code -}, {@code _} and {@code .}; the email domain is dropped and
     * anything else collapses to {@code -}. Never blank.
     */
    static String sanitize(String seed) {
        String local = seed == null ? "" : seed.trim();
        int at = local.indexOf('@');
        if (at >= 0) {
            local = local.substring(0, at);
        }
        String cleaned = local.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "")
                .replaceAll("\\.{2,}", ".");
        if (cleaned.isEmpty()) {
            cleaned = "user";
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    /**
     * Whether {@code name} may be claimed as-is — used for organization names, which (unlike
     * usernames) are chosen by the user rather than derived. Organizations and users share the
     * namespace, so the same reserved list and case-insensitive check apply.
     */
    public boolean isAvailable(String name) {
        return name != null && !name.isBlank() && isFree(name.toLowerCase(Locale.ROOT));
    }

    private boolean isFree(String candidate) {
        return !RESERVED.contains(candidate) && !userRepository.existsByNameIgnoreCase(candidate);
    }

    private static String withSuffix(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        return (base.length() > room ? base.substring(0, room) : base) + suffix;
    }
}
