package org.os.gitbase.git.entity.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How a pull request lands on its target branch: {@code merge}, {@code squash} or {@code rebase}.
 *
 * <p>Rebase-then-merge-commit, fast-forward-only and "manually merged" are deliberately not
 * supported yet.
 */
public enum MergeMethod {

    /** A two-parent merge commit on the target; the source history is kept as-is. */
    MERGE("merge"),

    /** One new commit on the target containing the whole diff; the source history is discarded. */
    SQUASH("squash"),

    /** Each source commit is replayed onto the target — linear history, no merge commit. */
    REBASE("rebase");

    private final String wireName;

    MergeMethod(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase name used in the API ({@code mergeMethod}). */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses the API value; blank means {@link #MERGE}.
     *
     * @throws IllegalArgumentException for an unknown method (mapped to 400)
     */
    public static MergeMethod parse(String value) {
        if (value == null || value.isBlank()) {
            return MERGE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(method -> method.wireName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown merge method '" + value + "' (expected one of "
                                + Arrays.stream(values()).map(MergeMethod::wireName)
                                        .collect(Collectors.joining(", ")) + ")"));
    }
}
