package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.GitToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GitTokenRepository extends JpaRepository<GitToken, Long> {
    @Query("SELECT t FROM GitToken t WHERE t.user.name = :username")
    List<GitToken> findByUsername(@Param("username") String username);

    @Query("SELECT t FROM GitToken t WHERE t.user.email = :email")
    List<GitToken> findByEmail(@Param("email") String email);
}
