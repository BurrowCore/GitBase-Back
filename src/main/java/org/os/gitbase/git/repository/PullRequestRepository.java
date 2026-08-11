package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.PullRequest;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.PullRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByRepositoryAndNumber(RepositoryGit repository, int number);

    /** Paginated listing; every list endpoint is paginated per ADR-004. */
    Page<PullRequest> findByRepository(RepositoryGit repository, Pageable pageable);

    Page<PullRequest> findByRepositoryAndStatus(RepositoryGit repository, PullRequestStatus status, Pageable pageable);

    /** Duplicate-detection for US-001: one open PR per source→target pair. */
    Optional<PullRequest> findByRepositoryAndSourceBranchAndTargetBranchAndStatus(
            RepositoryGit repository, String sourceBranch, String targetBranch, PullRequestStatus status);

    long countByRepositoryAndStatus(RepositoryGit repository, PullRequestStatus status);

    /**
     * Source branches that already have an open pull request, so the "recent pushes" banner can
     * skip them. Hits the {@code (repository_id, status)} index and returns only the branch names.
     */
    @Query("SELECT pr.sourceBranch FROM PullRequest pr WHERE pr.repository = :repository AND pr.status = 'OPEN'")
    List<String> findOpenSourceBranches(@Param("repository") RepositoryGit repository);

    /**
     * Highest number issued in this repository, or {@code null} if it has no pull requests yet.
     * Scoped to one repository so it never scans the whole table.
     */
    @Query("SELECT MAX(pr.number) FROM PullRequest pr WHERE pr.repository = :repository")
    Integer findMaxNumber(@Param("repository") RepositoryGit repository);
}
