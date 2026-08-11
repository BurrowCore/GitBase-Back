package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request body for opening an issue. */
public record CreateIssueDto(
        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must be at most 255 characters")
        String title,

        @Size(max = 65535, message = "Body must be at most 65535 characters")
        String body,

        /** Label names; unknown names are rejected. */
        List<String> labels,

        /** Usernames to assign; unknown users are rejected. */
        List<String> assignees
) {}
