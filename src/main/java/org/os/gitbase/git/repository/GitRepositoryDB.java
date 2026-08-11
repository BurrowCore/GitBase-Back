package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface GitRepositoryDB extends JpaRepository<RepositoryGit, UUID> {
    List<RepositoryGit> findByOwner(User owner);
    Optional<RepositoryGit> findByOwnerNameAndRepoName(String ownerName, String repoName);

    /** All repositories with their owner eagerly loaded (for non-transactional startup work). */
    @Query("SELECT r FROM RepositoryGit r JOIN FETCH r.owner")
    List<RepositoryGit> findAllWithOwner();
}
