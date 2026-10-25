package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.IssueState;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Filtering is done with {@link IssueSpecifications} through {@link JpaSpecificationExecutor}
 * rather than a single JPQL query with {@code (:param IS NULL OR …)} guards — see that class for
 * why (a null bound into {@code lower(…)} breaks on PostgreSQL, and the guards defeat the index).
 */
@org.springframework.stereotype.Repository
public interface IssueRepository extends JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {

    Optional<Issue> findByRepositoryAndNumber(RepositoryGit repository, int number);

    long countByRepositoryAndState(RepositoryGit repository, IssueState state);

    /** Which of {@code numbers} are issues in this repository (the rest may be pull requests). */
    @Query("SELECT i.number FROM Issue i WHERE i.repository = :repository AND i.number IN :numbers ORDER BY i.number")
    List<Integer> findExistingNumbers(@Param("repository") RepositoryGit repository,
                                      @Param("numbers") Collection<Integer> numbers);

    /** Open issues among {@code numbers} — the ones a merged pull request may close. */
    List<Issue> findByRepositoryAndStateAndNumberIn(RepositoryGit repository, IssueState state,
                                                    Collection<Integer> numbers);

    /** Row lock for serialising dependency changes; the returned id is irrelevant. Needs a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i.id FROM Issue i WHERE i.id = :id")
    Optional<Long> lockForUpdate(@Param("id") Long id);

    /** Highest issue number in this repository; feeds the shared allocator. */
    @Query("SELECT MAX(i.number) FROM Issue i WHERE i.repository = :repository")
    Integer findMaxNumber(@Param("repository") RepositoryGit repository);
}
