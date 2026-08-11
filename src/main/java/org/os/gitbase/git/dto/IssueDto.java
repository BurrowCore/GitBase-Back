package org.os.gitbase.git.dto;

import java.util.List;

/** Full issue payload. */
public record IssueDto(
        int number,
        String title,
        String body,
        String state,                  // OPEN | CLOSED
        UserSummaryDto author,
        List<LabelDto> labels,
        List<UserSummaryDto> assignees,
        long commentsCount,
        UserSummaryDto closedBy,
        Long createdAt,                // epoch millis
        Long updatedAt,
        Long closedAt
) {}
