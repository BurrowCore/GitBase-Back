package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.IssueState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

@org.springframework.stereotype.Repository
public interface IssueRepository extends JpaRepository<Issue, Long> {

    Optional<Issue> findByRepositoryAndNumber(RepositoryGit repository, int number);

    long countByRepositoryAndState(RepositoryGit repository, IssueState state);

    /** Highest issue number in this repository; feeds the shared allocator. */
    @Query("SELECT MAX(i.number) FROM Issue i WHERE i.repository = :repository")
    Integer findMaxNumber(@Param("repository") RepositoryGit repository);

    /**
     * Filtered, paginated listing. Every filter is optional — a null means "don't filter on this".
     * Doing it in one query keeps listing off the N+1 path (ADR-004); the label and assignee
     * filters use EXISTS rather than a join so a match can't duplicate rows.
     */
    @Query(value = """
            SELECT i FROM Issue i
            WHERE i.repository = :repository
              AND (:state IS NULL OR i.state = :state)
              AND (:label IS NULL OR EXISTS (
                    SELECT 1 FROM Issue i2 JOIN i2.labels l
                    WHERE i2 = i AND l.name = :label))
              AND (:assignee IS NULL OR EXISTS (
                    SELECT 1 FROM Issue i3 JOIN i3.assignees a
                    WHERE i3 = i AND a.name = :assignee))
              AND (:search IS NULL OR LOWER(i.title) LIKE LOWER(CONCAT('%', :search, '%'))
                                   OR LOWER(i.body)  LIKE LOWER(CONCAT('%', :search, '%')))
            """,
            countQuery = """
            SELECT COUNT(i) FROM Issue i
            WHERE i.repository = :repository
              AND (:state IS NULL OR i.state = :state)
              AND (:label IS NULL OR EXISTS (
                    SELECT 1 FROM Issue i2 JOIN i2.labels l
                    WHERE i2 = i AND l.name = :label))
              AND (:assignee IS NULL OR EXISTS (
                    SELECT 1 FROM Issue i3 JOIN i3.assignees a
                    WHERE i3 = i AND a.name = :assignee))
              AND (:search IS NULL OR LOWER(i.title) LIKE LOWER(CONCAT('%', :search, '%'))
                                   OR LOWER(i.body)  LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Issue> search(@Param("repository") RepositoryGit repository,
                       @Param("state") IssueState state,
                       @Param("label") String label,
                       @Param("assignee") String assignee,
                       @Param("search") String search,
                       Pageable pageable);
}
