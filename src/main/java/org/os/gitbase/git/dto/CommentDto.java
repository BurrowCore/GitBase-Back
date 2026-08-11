package org.os.gitbase.git.dto;

/** A single pull-request comment. */
public record CommentDto(
        Long id,
        UserSummaryDto author,
        String body,
        Long createdAt,
        Long updatedAt
) {}
