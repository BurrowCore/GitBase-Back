package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for adding or updating a collaborator.
 *
 * @param permission READ, WRITE or ADMIN (case-insensitive)
 */
public record SetCollaboratorDto(
        @NotBlank(message = "Permission is required")
        @Pattern(regexp = "(?i)READ|WRITE|ADMIN", message = "Permission must be READ, WRITE or ADMIN")
        String permission
) {}
