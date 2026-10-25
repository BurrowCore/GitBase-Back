package org.os.gitbase.git.service;

import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.IssueRepository;
import org.os.gitbase.git.repository.PullRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out the per-repository {@code #number} shared by issues and pull requests.
 *
 * <p>Issues and PRs draw from a <b>single</b> sequence, so a repository reads
 * #1, #2, #3 whatever the type. That is what makes a bare "#12" reference unambiguous, and it is
 * why this allocator exists rather than each table keeping its own counter.
 *
 * <p>Concurrency: the repository row is locked {@code PESSIMISTIC_WRITE} for the duration of the
 * allocation, so two simultaneous creates cannot claim the same number. The unique constraints on
 * {@code (repository_id, number)} in both tables remain the last line of defence.
 *
 * <p>Self-healing: the stored counter is reconciled against the actual maxima in both tables on
 * every call. That lets it start from zero on repositories created before the column existed —
 * their existing pull-request numbers are respected instead of being handed out a second time.
 */
@Service
public class IssueNumberAllocator {

    private final GitRepositoryDB repositoryDB;
    private final IssueRepository issueRepository;
    private final PullRequestRepository pullRequestRepository;

    public IssueNumberAllocator(GitRepositoryDB repositoryDB,
                                IssueRepository issueRepository,
                                PullRequestRepository pullRequestRepository) {
        this.repositoryDB = repositoryDB;
        this.issueRepository = issueRepository;
        this.pullRequestRepository = pullRequestRepository;
    }

    /**
     * Reserves and returns the next {@code #number} for the given repository.
     *
     * <p>Joins the caller's transaction ({@code MANDATORY}) on purpose: the number must be rolled
     * back with the issue or pull request it was allocated for, otherwise a failed create would
     * burn a number and leave a gap.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int allocate(RepositoryGit repository) {
        RepositoryGit locked = repositoryDB.findByIdForUpdate(repository.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + repository.getRepoName()));

        int highestIssue = orZero(issueRepository.findMaxNumber(locked));
        int highestPull = orZero(pullRequestRepository.findMaxNumber(locked));
        int next = Math.max(orZero(locked.getLastIssueNumber()), Math.max(highestIssue, highestPull)) + 1;

        locked.setLastIssueNumber(next);
        repositoryDB.save(locked);
        return next;
    }

    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
