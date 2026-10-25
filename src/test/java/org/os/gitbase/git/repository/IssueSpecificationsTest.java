package org.os.gitbase.git.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.IssueState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Executes the issue-list query against the real database.
 *
 * <p>These filters cannot be verified by a unit test: the bug they exist to prevent was a
 * <b>runtime</b> SQL failure. The previous single-JPQL implementation bound a null {@code :search}
 * into {@code lower(concat('%', ?, '%'))}; with no inferable type PostgreSQL defaulted it to
 * {@code bytea} and the statement died with <em>function lower(bytea) does not exist</em>, even
 * though the {@code IS NULL OR} guard should logically have short-circuited it — the expression is
 * still typed at plan time. Only issuing the SQL proves it is gone.
 *
 * <p>Named {@code …Test} rather than {@code …IT} so Surefire actually runs it as part of
 * {@code mvn test} — the project has no Failsafe setup, and an integration test that never runs is
 * worse than none. Like {@code GitbaseApplicationTests} it needs a reachable PostgreSQL.
 *
 * <p>Creates its own owner + repository inside the test transaction, which is rolled back, so it
 * neither mutates the developer's database nor depends on it already holding data.
 */
@SpringBootTest
@Transactional
class IssueSpecificationsTest {

    @Autowired
    private IssueRepository issueRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private RepositoryGit repository;
    private final Pageable page = PageRequest.of(0, 30, Sort.by(Sort.Direction.DESC, "number"));

    @BeforeEach
    void createFixtures() {
        User owner = new User();
        owner.setName("spec-test-owner");
        owner.setEmail("spec-test-owner@example.test");
        entityManager.persist(owner);

        repository = new RepositoryGit();
        repository.setOwner(owner);
        repository.setRepoName("spec-test-repo");
        repository.setPrivate(false);
        repository.setCreatedAt(java.time.LocalDateTime.now());
        entityManager.persist(repository);

        // Flush so the rows exist for the queries below; the transaction still rolls back after.
        entityManager.flush();
    }

    private Specification<Issue> spec(IssueState state, String label, String assignee, String search) {
        return Specification.where(IssueSpecifications.inRepository(repository))
                .and(IssueSpecifications.hasState(state))
                .and(IssueSpecifications.hasLabel(label))
                .and(IssueSpecifications.hasAssignee(assignee))
                .and(IssueSpecifications.matches(search));
    }

    @Test
    void listsWithNoFiltersAtAll() {
        assertThatCode(() -> issueRepository.findAll(spec(null, null, null, null), page).getContent())
                .doesNotThrowAnyException();
    }

    @Test
    void listsFilteredByStateOnly() {
        // The exact call the UI makes on load: ?state=OPEN&page=0
        assertThatCode(() -> issueRepository.findAll(spec(IssueState.OPEN, null, null, null), page).getContent())
                .doesNotThrowAnyException();
    }

    @Test
    void listsWithASearchTerm() {
        assertThatCode(() -> issueRepository.findAll(spec(null, null, null, "crash"), page).getContent())
                .doesNotThrowAnyException();
    }

    @Test
    void listsFilteredByLabel() {
        assertThatCode(() -> issueRepository.findAll(spec(null, "bug", null, null), page).getContent())
                .doesNotThrowAnyException();
    }

    @Test
    void listsFilteredByAssignee() {
        assertThatCode(() -> issueRepository.findAll(spec(null, null, "someone", null), page).getContent())
                .doesNotThrowAnyException();
    }

    @Test
    void listsWithEveryFilterCombined() {
        // Exercises the count query too, which is where a distinct-on-count would blow up.
        assertThatCode(() -> {
            var result = issueRepository.findAll(spec(IssueState.OPEN, "bug", "someone", "crash"), page);
            result.getTotalElements();
            result.getContent();
        }).doesNotThrowAnyException();
    }
}
