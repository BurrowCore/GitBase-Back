package org.os.gitbase.git.dto;

import java.util.List;

/** One page of pull-request comments. */
public record CommentPageDto(
        List<CommentDto> comments,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {}
