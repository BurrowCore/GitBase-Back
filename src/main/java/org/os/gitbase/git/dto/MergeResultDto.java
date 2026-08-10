package org.os.gitbase.git.dto;

/** Outcome of a successful merge (US-003). */
public record MergeResultDto(boolean merged, String sha, String message) {}
