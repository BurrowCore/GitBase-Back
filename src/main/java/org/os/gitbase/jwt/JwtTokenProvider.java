package org.os.gitbase.jwt;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Signs and reads access tokens. Refresh tokens and sessions live in
 * {@code AuthSessionService}; every access token carries its session id ({@code sid}), which the
 * authentication filter checks on each request so a signed-out session's tokens stop working.
 */
public interface JwtTokenProvider {

    String SESSION_CLAIM = "sid";

    String extractUsername(String token);

    Date extractExpiration(String token);

    <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

    Claims extractAllClaims(String token);

    Boolean isTokenExpired(String token);

    Boolean validateToken(String token, UserDetails userDetails);

    /** Signed access token for {@code username} in session {@code sessionId}, valid for {@code ttl}. */
    String generateToken(String username, List<String> roles, UUID sessionId, Duration ttl);

    /**
     * The session id of a token whose signature is valid — even if it has expired, so sign-out can
     * still end the session. Empty for tokens without one (issued before sessions existed).
     */
    Optional<UUID> extractSessionId(String token);

    PrivateKey loadPrivateKey(String key) throws Exception;

    PublicKey loadPublicKey(String key) throws Exception;
}
