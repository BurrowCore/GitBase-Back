package org.os.gitbase.git.dto;

/**
 * Row in the pull-request list. Deliberately excludes commit/diff counts: computing those needs a
 * RevWalk per row, which would make listing O(rows x history) (ADR-004).
 */
public record PullRequestSummaryDto(
        Long id,
        int number,
        String title,
        String state,
        String sourceBranch,
        String targetBranch,
        UserSummaryDto author,
        long commentsCount,
        Long createdAt,
        Long updatedAt
) {}
