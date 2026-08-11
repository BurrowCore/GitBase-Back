package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface GitRepositoryDB extends JpaRepository<RepositoryGit, UUID> {
    List<RepositoryGit> findByOwner(User owner);
    Optional<RepositoryGit> findByOwnerNameAndRepoName(String ownerName, String repoName);

    /**
     * Locks the repository row so a #number can be allocated without two concurrent creates
     * claiming the same one. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RepositoryGit r WHERE r.id = :id")
    Optional<RepositoryGit> findByIdForUpdate(@Param("id") UUID id);

    /** All repositories with their owner eagerly loaded (for non-transactional startup work). */
    @Query("SELECT r FROM RepositoryGit r JOIN FETCH r.owner")
    List<RepositoryGit> findAllWithOwner();
}
