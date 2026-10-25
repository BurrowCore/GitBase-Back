package org.os.gitbase.git.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.git.dto.IssueRefDto;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.IssueState;
import org.os.gitbase.git.repository.IssueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dependencies and closing-keyword lookups against the real database — the new JPQL
 * ({@code findDependenciesOf}, {@code findBlockerNumbers}, {@code findDependencyIdsOf},
 * {@code findExistingNumbers}) only proves itself when PostgreSQL runs it.
 *
 * <p>Creates its own fixtures inside the test transaction, which is rolled back. Needs a reachable
 * PostgreSQL and the {@code PRIV_KEY}/{@code PUB_KEY} variables, like {@code GitbaseApplicationTests}.
 */
@SpringBootTest
@Transactional
class IssueDependencyIntegrationTest {

    private static final String OWNER = "dep-test-owner";
    private static final String REPO = "dep-test-repo";

    @Autowired
    private IssueDependencyService dependencies;

    @Autowired
    private IssueService issues;

    @Autowired
    private IssueRepository issueRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private RepositoryGit repository;

    @BeforeEach
    void createFixtures() {
        User owner = new User();
        owner.setName(OWNER);
        owner.setEmail(OWNER + "@example.test");
        entityManager.persist(owner);

        repository = new RepositoryGit();
        repository.setOwner(owner);
        repository.setRepoName(REPO);
        repository.setPrivate(false);
        repository.setCreatedAt(java.time.LocalDateTime.now());
        entityManager.persist(repository);

        for (int number = 1; number <= 3; number++) {
            Issue issue = new Issue();
            issue.setRepository(repository);
            issue.setNumber(number);
            issue.setTitle("Issue " + number);
            issue.setAuthor(owner);
            entityManager.persist(issue);
        }
        entityManager.flush();
    }

    @Test
    void recordsAndListsDependenciesInBothDirections() {
        dependencies.addDependency(OWNER, REPO, 2, 1, OWNER, true);
        dependencies.addDependency(OWNER, REPO, 3, 1, OWNER, true);

        assertThat(dependencies.dependencies(OWNER, REPO, 2, 0, 30).issues())
                .extracting(IssueRefDto::number).containsExactly(1);
        assertThat(dependencies.dependents(OWNER, REPO, 1, 0, 30).issues())
                .extracting(IssueRefDto::number).containsExactly(2, 3);
    }

    @Test
    void rejectsDuplicatesAndAnyCycle() {
        dependencies.addDependency(OWNER, REPO, 2, 1, OWNER, true);
        dependencies.addDependency(OWNER, REPO, 3, 2, OWNER, true);
        entityManager.flush();

        assertThatThrownBy(() -> dependencies.addDependency(OWNER, REPO, 2, 1, OWNER, true))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already depends");
        // 1 → 3 would close 3 → 2 → 1: indirect cycles are caught, not only the direct pair.
        assertThatThrownBy(() -> dependencies.addDependency(OWNER, REPO, 1, 3, OWNER, true))
                .isInstanceOf(ConflictException.class).hasMessageContaining("circular");
        assertThatThrownBy(() -> dependencies.addDependency(OWNER, REPO, 1, 1, OWNER, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOpenBlockerPreventsClosingByHand_untilItIsClosed() {
        dependencies.addDependency(OWNER, REPO, 2, 1, OWNER, true);
        entityManager.flush();

        assertThatThrownBy(() -> issues.close(OWNER, REPO, 2, OWNER, true))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("#1");

        issues.close(OWNER, REPO, 1, OWNER, true);
        entityManager.flush();
        assertThatCode(() -> issues.close(OWNER, REPO, 2, OWNER, true)).doesNotThrowAnyException();
    }

    @Test
    void evenTheAuthorNeedsWriteAccessToChangeDependencies() {
        // Otherwise any reader of a public repo could attach their issue to every other.
        assertThatThrownBy(() -> dependencies.addDependency(OWNER, REPO, 2, 1, OWNER, false))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void reopeningClearsTheClosingPullRequest() {
        Issue issue = issueRepository.findByRepositoryAndNumber(repository, 1).orElseThrow();
        issue.setState(IssueState.CLOSED);
        issue.setClosedByPullNumber(7);
        entityManager.flush();

        assertThat(issues.reopen(OWNER, REPO, 1, OWNER, true).closedByPullNumber()).isNull();
    }

    @Test
    void closingKeywordLookupsKeepOnlyRealOpenIssues() {
        // #99 does not exist (or is a pull request in the shared sequence) and is dropped.
        assertThat(issueRepository.findExistingNumbers(repository, List.of(3, 1, 99))).containsExactly(1, 3);
        assertThat(issueRepository.findByRepositoryAndStateAndNumberIn(repository, IssueState.OPEN, List.of(1, 2)))
                .hasSize(2);
    }
}
