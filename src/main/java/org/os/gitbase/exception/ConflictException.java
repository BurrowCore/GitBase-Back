package org.os.gitbase.exception;

import java.util.List;

/**
 * Thrown when a request collides with the current state of a resource — a duplicate open pull
 * request, or a merge that cannot be applied because of conflicting changes.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    /** Conflicting file paths, when the conflict came from a merge. Empty otherwise. */
    private final List<String> conflicts;

    public ConflictException(String message) {
        this(message, List.of());
    }

    public ConflictException(String message, List<String> conflicts) {
        super(message);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public List<String> getConflicts() {
        return conflicts;
    }
}
