package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueDependency;
import org.os.gitbase.git.entity.enums.IssueState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueDependencyRepository extends JpaRepository<IssueDependency, Long> {

    boolean existsByIssueAndDependency(Issue issue, Issue dependency);

    Optional<IssueDependency> findByIssueAndDependency(Issue issue, Issue dependency);

    /** Issues that {@code issue} depends on ("blocked by"). */
    @Query(value = "SELECT d.dependency FROM IssueDependency d WHERE d.issue = :issue ORDER BY d.dependency.number",
            countQuery = "SELECT COUNT(d) FROM IssueDependency d WHERE d.issue = :issue")
    Page<Issue> findDependenciesOf(@Param("issue") Issue issue, Pageable pageable);

    /** Issues that depend on {@code issue} ("blocks"). */
    @Query(value = "SELECT d.issue FROM IssueDependency d WHERE d.dependency = :issue ORDER BY d.issue.number",
            countQuery = "SELECT COUNT(d) FROM IssueDependency d WHERE d.dependency = :issue")
    Page<Issue> findDependentsOf(@Param("issue") Issue issue, Pageable pageable);

    /** Open issues still blocking {@code issue}, lowest number first — what stops it being closed. */
    @Query("SELECT d.dependency.number FROM IssueDependency d "
            + "WHERE d.issue = :issue AND d.dependency.state = :state ORDER BY d.dependency.number")
    List<Integer> findBlockerNumbers(@Param("issue") Issue issue, @Param("state") IssueState state,
                                     Pageable limit);

    /** One breadth-first step of the cycle check: what the given issues depend on. */
    @Query("SELECT d.dependency.id FROM IssueDependency d WHERE d.issue.id IN :issueIds")
    List<Long> findDependencyIdsOf(@Param("issueIds") Collection<Long> issueIds);
}
