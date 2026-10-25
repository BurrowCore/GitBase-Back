package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Closes the issues a merged pull request references with a closing keyword ("Fixes #12").
 *
 * <p>Runs only <b>after the merge commits</b> and in its own transaction, so a failure here can
 * never roll back a merge that is already on the target branch — it is logged instead.
 *
 * <p>Only merges into the repository's <b>default branch</b> close issues: merging into a feature
 * branch has not shipped the fix yet.
 */
@Slf4j
@Component
public class IssueAutoCloseListener {

    private final IssueService issueService;

    public IssueAutoCloseListener(IssueService issueService) {
        this.issueService = issueService;
    }

    /**
     * Deliberately not {@code @Transactional}: {@link IssueService#closeByPullRequest} opens its own
     * {@code REQUIRES_NEW} transaction, so if it fails its rollback is complete before the catch
     * below — nothing rollback-only leaks back into the already-committed merge request.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPullRequestMerged(PullRequestMergedEvent event) {
        if (!event.intoDefaultBranch() || event.closingIssueNumbers().isEmpty()) {
            return;
        }
        try {
            List<Integer> closed = issueService.closeByPullRequest(event.repositoryId(),
                    event.closingIssueNumbers(), event.mergedBy(), event.pullNumber());
            log.info("Pull request #{} in {}/{} closed issues {}", event.pullNumber(), event.owner(),
                    event.repoName(), closed);
        } catch (RuntimeException e) {
            log.error("Closing issues referenced by pull request #{} in {}/{} failed: {}",
                    event.pullNumber(), event.owner(), event.repoName(), e.getMessage(), e);
        }
    }
}
