package org.os.gitbase.auth.repository;

import jakarta.persistence.LockModeType;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Locks the row: two requests presenting the same token must not both rotate it — the second
     * has to see it as used. Only the token row is locked; its session and user load lazily (a
     * locking query that join-fetches entities already in the persistence context fails in Hibernate).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String tokenHash);
}
