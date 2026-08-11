package org.os.gitbase.git.dto;

import java.util.List;

/**
 * Result of comparing two refs (the basis of a pull request). The diff is three-way: it shows
 * what {@code head} introduces relative to the merge base with {@code base} (so unrelated changes
 * already on {@code base} are excluded). {@code commits} are those reachable from head but not base.
 */
public record CompareDto(
        String base,
        String head,
        String mergeBase,                 // SHA of the merge base; null if the refs are unrelated
        int aheadBy,                      // commits in head not in base
        int behindBy,                     // commits in base not in head
        List<CommitSummaryDto> commits,   // the commits head adds over base (newest first)
        int totalAdditions,
        int totalDeletions,
        boolean truncated,                // true when the diff exceeded display limits
        List<FileDiffDto> files
) {}
