package org.os.gitbase.auth.repository;

import org.os.gitbase.auth.entity.jwt.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /** Runs on every authenticated request — a primary-key lookup. */
    boolean existsByIdAndRevokedAtIsNullAndExpiresAtAfter(UUID id, Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AuthSession s SET s.revokedAt = :now, s.revokeReason = :reason WHERE s.id = :id AND s.revokedAt IS NULL")
    int revoke(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);
}
