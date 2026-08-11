package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a branch.
 *
 * @param name the new branch name (validated against git's own ref-name rules by the service)
 * @param from branch, tag or SHA to branch from; defaults to the repository's HEAD when omitted
 */
public record CreateBranchDto(
        @NotBlank(message = "Branch name is required")
        @Size(max = 255, message = "Branch name must be at most 255 characters")
        String name,

        String from
) {}
