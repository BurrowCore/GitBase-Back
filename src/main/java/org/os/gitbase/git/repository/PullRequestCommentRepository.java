package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.PullRequest;
import org.os.gitbase.git.entity.PullRequestComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@org.springframework.stereotype.Repository
public interface PullRequestCommentRepository extends JpaRepository<PullRequestComment, Long> {

    /** Conversation order, paginated. JOIN FETCH avoids an N+1 on the lazy author. */
    @Query(value = "SELECT c FROM PullRequestComment c JOIN FETCH c.author WHERE c.pullRequest = :pr",
            countQuery = "SELECT COUNT(c) FROM PullRequestComment c WHERE c.pullRequest = :pr")
    Page<PullRequestComment> findByPullRequest(PullRequest pr, Pageable pageable);

    long countByPullRequest(PullRequest pr);
}
