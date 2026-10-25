package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;


@org.springframework.stereotype.Repository
public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {

    /** Conversation order, paginated. JOIN FETCH avoids an N+1 on the lazy author. */
    @Query(value = "SELECT c FROM IssueComment c JOIN FETCH c.author WHERE c.issue = :issue",
            countQuery = "SELECT COUNT(c) FROM IssueComment c WHERE c.issue = :issue")
    Page<IssueComment> findByIssue(Issue issue, Pageable pageable);

    long countByIssue(Issue issue);

    /**
     * Comment counts for a batch of issues in ONE query, keyed by issue id. Counting per row while
     * rendering a list would be a textbook N+1 (ADR-004).
     */
    @Query("SELECT c.issue.id, COUNT(c) FROM IssueComment c WHERE c.issue IN :issues GROUP BY c.issue.id")
    List<Object[]> countByIssues(@Param("issues") Collection<Issue> issues);
}
