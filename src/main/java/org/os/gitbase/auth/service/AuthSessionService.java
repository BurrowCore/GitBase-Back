package org.os.gitbase.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.jwt.AuthSession;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.os.gitbase.auth.repository.AuthSessionRepository;
import org.os.gitbase.auth.repository.RefreshTokenRepository;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in sessions and the tokens that belong to them.
 *
 * <ul>
 *   <li><b>Sign-in</b> creates an {@link AuthSession}, a short-lived access token (JWT carrying the
 *       session id as {@code sid}) and a refresh token.</li>
 *   <li><b>Refresh</b> is single-use rotation: the presented token is marked used and a new one is
 *       issued. Presenting a token that was already used (after a short {@link #REUSE_GRACE} for
 *       tabs refreshing concurrently) means someone kept a copy — the whole session is revoked,
 *       which also kills the attacker's (and the user's) access tokens.</li>
 *   <li><b>Sign-out</b> revokes the session: every access and refresh token of that sign-in stops
 *       working immediately, not when it expires.</li>
 * </ul>
 *
 * <p>Refresh tokens are 256-bit random values; only their SHA-256 is stored.
 */
@Slf4j
@Service
public class AuthSessionService {

    /** Short, because an access token can only be revoked by checking its session on each request. */
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    /** However often it is refreshed, a sign-in lasts at most this long. */
    public static final Duration SESSION_MAX_AGE = Duration.ofDays(30);
    /**
     * A just-rotated refresh token is still honoured this long, because several browser tabs can
     * refresh with the same cookie at the same moment. Past it, reuse is treated as theft.
     */
    public static final Duration REUSE_GRACE = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public AuthSessionService(AuthSessionRepository sessionRepository, RefreshTokenRepository refreshTokenRepository,
                              JwtTokenProvider jwtTokenProvider, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clock = clock;
    }

    /**
     * Tokens to hand to the browser, as cookies. {@code refreshToken} is null when only a new access
     * token was issued (a concurrent refresh inside {@link #REUSE_GRACE}).
     */
    public record IssuedTokens(String accessToken, String refreshToken, User user) {}

    /** A fresh sign-in for {@code user} (password or Google). */
    @Transactional
    public IssuedTokens signIn(User user) {
        Instant now = clock.instant();
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID());
        session.setUser(user);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(SESSION_MAX_AGE));
        sessionRepository.save(session);
        return issue(session, now);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token.
     *
     * @throws BadCredentialsException when the token is unknown, expired, already used (the session
     *                                 is then revoked) or its session has ended — answered with 401
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public IssuedTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = findLocked(rawRefreshToken)
                .orElseThrow(() -> new BadCredentialsException("Unknown refresh token"));
        AuthSession session = token.getSession();

        if (!session.isActiveAt(now) || !token.getExpiresAt().isAfter(now)) {
            throw new BadCredentialsException("Session ended — sign in again");
        }
        if (token.getUsedAt() != null) {
            if (token.getUsedAt().plus(REUSE_GRACE).isAfter(now)) {
                // Two tabs refreshing at once with the same cookie: not theft, so no sign-out. Only a
                // new access token, though: the rotated refresh token already reached the shared
                // cookie jar, and minting another would let a replay start a chain of its own.
                return new IssuedTokens(accessToken(session), null, session.getUser());
            }
            // Reuse of a rotated token: whoever holds it is not necessarily the user. End the session.
            sessionRepository.revoke(session.getId(), now, "REFRESH_TOKEN_REUSE");
            log.warn("Refresh token reused for session {} of user {} — session revoked",
                    session.getId(), session.getUser().getName());
            throw new BadCredentialsException("Refresh token already used");
        }

        token.setUsedAt(now);
        return issue(session, now);
    }

    /**
     * Ends the sign-in a refresh token belongs to. Unknown tokens are ignored — sign-out must
     * always succeed from the user's point of view.
     */
    @Transactional
    public void signOutByRefreshToken(String rawRefreshToken) {
        findLocked(rawRefreshToken).ifPresent(token ->
                sessionRepository.revoke(token.getSession().getId(), clock.instant(), "SIGN_OUT"));
    }

    /** Ends a sign-in by its id (the access token's {@code sid}). */
    @Transactional
    public void signOutBySessionId(UUID sessionId) {
        sessionRepository.revoke(sessionId, clock.instant(), "SIGN_OUT");
    }

    /** Whether access tokens of this session are still accepted. One primary-key lookup. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId) {
        return sessionRepository.existsByIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, clock.instant());
    }

    // ───────────────────────── helpers ─────────────────────────

    private IssuedTokens issue(AuthSession session, Instant now) {
        User user = session.getUser();
        String raw = newRefreshTokenValue();
        RefreshToken refresh = new RefreshToken();
        refresh.setTokenHash(sha256(raw));
        refresh.setSession(session);
        refresh.setCreatedAt(now);
        // Never outlives its session.
        Instant expiry = now.plus(REFRESH_TOKEN_TTL);
        refresh.setExpiresAt(expiry.isBefore(session.getExpiresAt()) ? expiry : session.getExpiresAt());
        refreshTokenRepository.save(refresh);
        return new IssuedTokens(accessToken(session), raw, user);
    }

    private String accessToken(AuthSession session) {
        User user = session.getUser();
        return jwtTokenProvider.generateToken(user.getUsername(),
                user.getRoles().stream().map(Role::getRoleName).toList(), session.getId(), ACCESS_TOKEN_TTL);
    }

    private Optional<RefreshToken> findLocked(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByTokenHashForUpdate(sha256(raw));
    }

    private static String newRefreshTokenValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
