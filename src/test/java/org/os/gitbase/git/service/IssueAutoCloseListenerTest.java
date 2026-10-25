package org.os.gitbase.git.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueAutoCloseListenerTest {

    private final IssueService issueService = mock(IssueService.class);
    private final IssueAutoCloseListener listener = new IssueAutoCloseListener(issueService);
    private final UUID repoId = UUID.randomUUID();

    private PullRequestMergedEvent event(boolean intoDefault, Set<Integer> numbers) {
        return new PullRequestMergedEvent(repoId, "alice", "demo", 7, "carol", intoDefault, numbers);
    }

    @Test
    void closesReferencedIssuesOnAMergeIntoTheDefaultBranch() {
        listener.onPullRequestMerged(event(true, Set.of(3, 8)));
        verify(issueService).closeByPullRequest(repoId, Set.of(3, 8), "carol", 7);
    }

    @Test
    void mergeIntoAnotherBranchClosesNothing() {
        // GitHub's rule: the fix has not shipped until it reaches the default branch.
        listener.onPullRequestMerged(event(false, Set.of(3)));
        verify(issueService, never()).closeByPullRequest(any(), any(), anyString(), anyInt());
    }

    @Test
    void aFailureIsContained_soTheCommittedMergeIsUnaffected() {
        when(issueService.closeByPullRequest(any(), any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onPullRequestMerged(event(true, Set.of(3)))).doesNotThrowAnyException();
    }
}
