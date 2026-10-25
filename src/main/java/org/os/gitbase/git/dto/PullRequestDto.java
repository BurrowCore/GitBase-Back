package org.os.gitbase.git.dto;

import java.util.List;

/** Full pull-request payload (US-002). */
public record PullRequestDto(
        Long id,
        int number,
        String title,
        String description,
        String state,               // OPEN | MERGED | CLOSED
        String sourceBranch,
        String targetBranch,
        String headSha,             // source tip when the PR was opened (snapshot)
        String baseSha,
        String currentHeadSha,      // live source tip (OPEN only) — send back as expectedHeadSha on merge
        List<Integer> closingIssues, // issues a merge into the default branch will close ("Fixes #N")
        UserSummaryDto author,
        UserSummaryDto mergedBy,
        String mergeCommitSha,
        String mergeMethod,         // merge | squash | rebase — null until merged
        int commitsCount,
        int changedFilesCount,
        long commentsCount,
        Long createdAt,             // epoch millis
        Long updatedAt,
        Long mergedAt,
        Long closedAt
) {}
