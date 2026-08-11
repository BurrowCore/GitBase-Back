package org.os.gitbase.auth.repository;

import org.os.gitbase.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findUserByEmail(String email);


    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.roleName = 'CLIENT'")
    Optional<Long> countUsersWithClientRole();

    Optional<User> findUserByName(String name);
}
