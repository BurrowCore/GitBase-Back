package org.os.gitbase.jwt.jwtImpl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.os.gitbase.auth.repository.RefreshTokenRepository;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class JwtTokenProviderImpl implements JwtTokenProvider {
    private static final long JWT_TOKEN_VALIDITY = 24 * 60 * 60; // 24 hours
    private PrivateKey privateKey;
    private PublicKey publicKey;
    @Value("${jwt-keys.private_key}")
    private String privk;
    @Value("${jwt-keys.public_key}")
    private String pubk;
    final String ISSUER = "http://localhost:8880";
    final String AUDIENCE = "http://localhost:8880";
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    public JwtTokenProviderImpl(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

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

    public String generateToken(String username, List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, username, roles);
    }

    @Override
    public String createToken(Map<String, Object> claims, String username, List<String> roles) {
        claims.put("roles", roles);

        return Jwts.builder().claims(claims)
                .subject(username)
                .issuer(ISSUER)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + JWT_TOKEN_VALIDITY * 1000))
                .signWith(privateKey)
                .audience().add(AUDIENCE)
                .and()
                .compact();
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

    @Override
    public RefreshToken createRefreshToken(String email) {
        Optional<RefreshToken> optionalToken = refreshTokenRepository.findRefreshTokenByEmail(email);
        if (optionalToken.isPresent()) {
            RefreshToken existingToken = optionalToken.get();
            if (existingToken.getExpiryDate().isAfter(Instant.now())) {
                existingToken.setExpiryDate(Instant.now().plusMillis(28800000));
                return refreshTokenRepository.save(existingToken);
            } else {
                refreshTokenRepository.delete(existingToken);
            }
        }
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(userRepository.findUserByEmail(email).get())
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(28800000))
                .build();
        return refreshTokenRepository.save(newRefreshToken);
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Override
    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new RuntimeException(token.getToken() + " Refresh token is expired. Please make a new login..!");
        }
        return token;
    }

    @Override
    public boolean validateRefreshToken(String token) {
        try {
            Optional<RefreshToken> optionalToken = refreshTokenRepository.findByToken(token);
            return optionalToken.isPresent() && optionalToken.get().getExpiryDate().isAfter(Instant.now());
        } catch (RuntimeException ex) {
            throw new RuntimeException("Refresh token not valide");
        }
    }
}
