package org.os.gitbase.git.dto;

/** Full pull-request payload (US-002). */
public record PullRequestDto(
        Long id,
        int number,
        String title,
        String description,
        String state,               // OPEN | MERGED | CLOSED
        String sourceBranch,
        String targetBranch,
        String headSha,
        String baseSha,
        UserSummaryDto author,
        UserSummaryDto mergedBy,
        String mergeCommitSha,
        int commitsCount,
        int changedFilesCount,
        long commentsCount,
        Long createdAt,             // epoch millis
        Long updatedAt,
        Long mergedAt,
        Long closedAt
) {}
