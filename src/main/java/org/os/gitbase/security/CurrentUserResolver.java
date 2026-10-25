package org.os.gitbase.security;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;

/**
 * Maps an authenticated {@link Principal} to the {@link User} it belongs to.
 *
 * <p>The principal's name differs by login method: browser sessions (JWT cookie, Google OAuth)
 * carry the <b>email</b>, while git over HTTP carries the <b>username</b> typed into the client.
 * The app previously derived identity by cutting the email at {@code @}, so
 * {@code alice@evil.com} was treated as user {@code alice} — the owner of every one of
 * {@code alice@company.com}'s repositories. Identity is now always the stored, unique username of
 * the account the principal actually authenticated as.
 *
 * <p>One indexed lookup per call (email and name are both unique) — safe on every request.
 */
@Slf4j
@Component
public class CurrentUserResolver {

    private final UserRepository userRepository;

    public CurrentUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The account behind the principal; empty when anonymous, when no such account exists, or when
     * its username is <b>not unique</b>.
     *
     * <p>The last case fails closed on purpose: accounts created before usernames were made unique
     * can share a name ({@code alice@company.com} and {@code alice@evil.com} both stored as
     * {@code alice}), and every permission check compares names. Until an operator renames them
     * (runbook in {@code backend/docs/database-schema.md}), such accounts are treated as
     * unauthenticated rather than as each other. One indexed count per call — no startup scan.
     */
    public Optional<User> resolve(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return Optional.empty();
        }
        String name = principal.getName();
        if (!name.contains("@") && isAmbiguous(name)) {
            return Optional.empty(); // checked first: findUserByName would throw on duplicates
        }
        Optional<User> account = name.contains("@")
                ? userRepository.findUserByEmail(name)
                : userRepository.findUserByName(name);
        return account.filter(user -> !isAmbiguous(user.getName()));
    }

    private boolean isAmbiguous(String username) {
        boolean ambiguous = userRepository.countByNameIgnoreCase(username) > 1;
        if (ambiguous) {
            log.warn("Refusing ambiguous username '{}': shared by several accounts — rename them", username);
        }
        return ambiguous;
    }

    /** The caller's username, or null when the principal does not map to an account. */
    public String usernameOrNull(Principal principal) {
        return resolve(principal).map(User::getName).orElse(null);
    }

    /**
     * The caller's username.
     *
     * @throws AuthenticationCredentialsNotFoundException when the principal does not map to an
     *                                                    account (mapped to 401)
     */
    public String requireUsername(Principal principal) {
        String username = usernameOrNull(principal);
        if (username == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return username;
    }
}
