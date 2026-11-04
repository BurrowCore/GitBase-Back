package org.os.gitbase.auth.entity.jwt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single-use refresh token. Using it marks it {@link #usedAt} and issues a new one in the same
 * {@link AuthSession} (rotation); presenting a used token again means it was copied, so the session
 * is revoked.
 *
 * <p>Only the SHA-256 of the token is stored — a leaked database row cannot be replayed.
 * Replaces the former {@code t_refresh_tokens} table (one reusable plaintext token per user).
 */
@Entity
@Table(name = "auth_refresh_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_refresh_token_session", columnList = "session_id"))
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hex SHA-256 of the token value sent to the browser. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSession session;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When it was exchanged for a new one; null while still unused. */
    @Column(name = "used_at")
    private Instant usedAt;
}
