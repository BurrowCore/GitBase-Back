package org.os.gitbase.git.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Merge request body.
 *
 * @param mergeMethod     {@code merge} (default), {@code squash} or {@code rebase} — GitHub's names
 * @param commitMessage   overrides the default message of the merge or squash commit; ignored by
 *                        rebase, which keeps each commit's own message
 * @param expectedHeadSha the source-branch tip the merger reviewed; if the branch has moved since,
 *                        the merge is refused with 409. Optional.
 */
public record MergePullRequestDto(
        @Pattern(regexp = "(?i)merge|squash|rebase", message = "mergeMethod must be merge, squash or rebase")
        String mergeMethod,
        @Size(max = 10000, message = "Commit message must be at most 10000 characters")
        String commitMessage,
        @Pattern(regexp = "[0-9a-fA-F]{40}", message = "expectedHeadSha must be a 40-character commit SHA")
        String expectedHeadSha
) {}
