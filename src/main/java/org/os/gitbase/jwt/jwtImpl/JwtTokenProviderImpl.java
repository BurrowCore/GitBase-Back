package org.os.gitbase.jwt.jwtImpl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

@Service
public class JwtTokenProviderImpl implements JwtTokenProvider {
    private PrivateKey privateKey;
    private PublicKey publicKey;
    @Value("${jwt-keys.private_key}")
    private String privk;
    @Value("${jwt-keys.public_key}")
    private String pubk;
    final String ISSUER = "http://localhost:8880";
    final String AUDIENCE = "http://localhost:8880";

    @PostConstruct
    public void init() {
        try {
            this.privateKey = loadPrivateKey(privk);
            this.publicKey = loadPublicKey(pubk);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load RSA keys", e);
        }
    }

    @Override
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    @Override
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    @Override
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    @Override
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public Boolean isTokenExpired(String token) {
        final Date expiration = extractExpiration(token);
        return expiration.before(new Date());
    }

    @Override
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    @Override
    public String generateToken(String username, List<String> roles, UUID sessionId, Duration ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("roles", roles)
                .claim(SESSION_CLAIM, sessionId.toString())
                .issuer(ISSUER)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()))
                .signWith(privateKey)
                .audience().add(AUDIENCE)
                .and()
                .compact();
    }

    @Override
    public Optional<UUID> extractSessionId(String token) {
        Claims claims;
        try {
            claims = extractAllClaims(token);
        } catch (ExpiredJwtException expired) {
            claims = expired.getClaims(); // signature was verified before the expiry check
        }
        Object sid = claims.get(SESSION_CLAIM);
        if (sid == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(sid.toString()));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    @Override
    public PrivateKey loadPrivateKey(String key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    @Override
    public PublicKey loadPublicKey(String key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }
}
