package org.os.gitbase.git.dto;

import java.util.List;

/** One page of issues, plus open/closed tallies for the list-page tabs. */
public record IssuePageDto(
        List<IssueSummaryDto> issues,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long openCount,
        long closedCount
) {}
