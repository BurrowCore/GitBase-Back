package org.os.gitbase.git.dto;

import java.util.List;

/** One page of pull requests, plus open/closed tallies for the list-page tabs. */
public record PullRequestPageDto(
        List<PullRequestSummaryDto> pullRequests,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long openCount,
        long closedCount
) {}
