package org.os.gitbase.git.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Partial update of an issue. Every field is optional — {@code null} means "leave unchanged",
 * which is what distinguishes "don't touch the labels" from "remove all labels" (an empty list).
 */
public record UpdateIssueDto(
        @Size(max = 255, message = "Title must be at most 255 characters")
        String title,

        @Size(max = 65535, message = "Body must be at most 65535 characters")
        String body,

        List<String> labels,

        List<String> assignees
) {}
