package org.os.gitbase.git.dto;

/**
 * A branch that was recently pushed and has no open pull request yet — the data behind the
 * GitHub-style "<branch> had recent pushes … Compare &amp; pull request" banner.
 *
 * <p>Read live from git, so a branch pushed over the CLI shows up on the next page load without
 * waiting for any mirror/sync step.
 */
public record PullSuggestionDto(
        String branch,
        String commitSha,
        String shortSha,
        String lastCommitMessage,
        String authorName,
        long lastCommitAt      // epoch millis of the branch tip's commit time
) {}
