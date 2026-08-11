package org.os.gitbase.git.dto;

import java.util.List;

/** Row in the issue list. */
public record IssueSummaryDto(
        int number,
        String title,
        String state,
        UserSummaryDto author,
        List<LabelDto> labels,
        List<UserSummaryDto> assignees,
        long commentsCount,
        Long createdAt,
        Long updatedAt
) {}
