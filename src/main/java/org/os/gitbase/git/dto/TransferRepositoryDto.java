package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to move a repository into an organization. */
public record TransferRepositoryDto(
        @NotBlank(message = "newOwner is required")
        @Size(max = 39)
        String newOwner
) {}
