package org.os.gitbase.auth.entity.jwt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * One sign-in — a browser on a device. Every access token carries this session's id ({@code sid})
 * and every refresh token belongs to it, so revoking the session (sign-out, or a stolen refresh
 * token being replayed) makes all of them unusable at once, before their natural expiry.
 */
@Entity
@Table(name = "auth_sessions", indexes = @Index(name = "idx_auth_session_user", columnList = "user_id"))
@Getter
@Setter
public class AuthSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Absolute limit: however often it is refreshed, a session ends here and the user signs in again. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the session is ended; a revoked session never becomes active again. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 32)
    private String revokeReason;

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
