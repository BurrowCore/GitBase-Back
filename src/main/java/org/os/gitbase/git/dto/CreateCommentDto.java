package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for adding a comment to a pull request's conversation. */
public record CreateCommentDto(
        @NotBlank(message = "Comment body is required")
        @Size(max = 65535, message = "Comment must be at most 65535 characters")
        String body
) {}
