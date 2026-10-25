package org.os.gitbase.git.dto;

/**
 * Outcome of a successful merge (US-003).
 *
 * @param sha         new tip of the target branch: the merge commit, the squash commit, or the
 *                    last rebased commit (unchanged tip when the source was already contained)
 * @param mergeMethod merge | squash | rebase
 */
public record MergeResultDto(boolean merged, String sha, String mergeMethod, String message) {}
