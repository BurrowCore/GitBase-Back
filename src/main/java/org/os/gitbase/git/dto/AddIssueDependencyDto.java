package org.os.gitbase.git.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of "this issue depends on #number". Dependencies are same-repository only, so the number
 * suffices (a cross-repository link would need {@code owner} and {@code repo} as well).
 */
public record AddIssueDependencyDto(
        @NotNull(message = "number is required")
        @Min(value = 1, message = "number must be positive")
        Integer number
) {}
