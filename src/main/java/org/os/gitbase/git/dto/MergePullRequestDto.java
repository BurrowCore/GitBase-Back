package org.os.gitbase.git.dto;

import jakarta.validation.constraints.Size;

/**
 * Merge request body. Phase 2 supports the merge-commit strategy only, so {@code mergeMethod} is
 * accepted for forward-compatibility but must be "merge" (or omitted).
 */
public record MergePullRequestDto(
        String mergeMethod,
        @Size(max = 1000, message = "Commit message must be at most 1000 characters")
        String commitMessage
) {}
