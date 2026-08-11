package org.os.gitbase.jwt;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.springframework.security.core.userdetails.UserDetails;
import io.jsonwebtoken.Claims;
public interface JwtTokenProvider {
    String extractUsername(String token);

    Date extractExpiration(String token);

    <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

    Claims extractAllClaims(String token);

    Boolean isTokenExpired(String token);

    Boolean validateToken(String token, UserDetails userDetails);

    String generateToken(String username, List<String> role);

    String createToken(Map<String, Object> claims, String username, List<String> roles);

    PrivateKey loadPrivateKey(String key) throws Exception;

    PublicKey loadPublicKey(String key) throws Exception;

    RefreshToken createRefreshToken(String username);

    Optional<RefreshToken> findByToken(String token);

    RefreshToken verifyExpiration(RefreshToken token);

    boolean validateRefreshToken(String token);
}
