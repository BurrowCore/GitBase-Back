package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


@org.springframework.stereotype.Repository
public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {

    /** Conversation order, paginated. JOIN FETCH avoids an N+1 on the lazy author. */
    @Query(value = "SELECT c FROM IssueComment c JOIN FETCH c.author WHERE c.issue = :issue",
            countQuery = "SELECT COUNT(c) FROM IssueComment c WHERE c.issue = :issue")
    Page<IssueComment> findByIssue(Issue issue, Pageable pageable);

    long countByIssue(Issue issue);
}
