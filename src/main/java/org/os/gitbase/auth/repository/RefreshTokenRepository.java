package org.os.gitbase.auth.repository;
import java.util.Optional;


import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    @Query("SELECT rf FROM RefreshToken rf WHERE rf.user.email = :email")
    Optional<RefreshToken> findRefreshTokenByEmail(@Param("email") String email);
}