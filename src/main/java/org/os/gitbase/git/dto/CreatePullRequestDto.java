package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for opening a pull request (US-001). */
public record CreatePullRequestDto(
        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must be at most 255 characters")
        String title,

        @Size(max = 65535, message = "Description must be at most 65535 characters")
        String description,

        @NotBlank(message = "Source branch is required")
        String sourceBranch,

        @NotBlank(message = "Target branch is required")
        String targetBranch
) {}
