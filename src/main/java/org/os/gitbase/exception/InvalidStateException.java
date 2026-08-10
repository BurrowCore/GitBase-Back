package org.os.gitbase.exception;

/**
 * Thrown when an operation is not permitted for a resource's current lifecycle state — e.g.
 * merging a pull request that is already merged or closed.
 *
 * <p>Mapped to HTTP 405 by {@link GlobalExceptionHandler}, matching the pull-request feature
 * spec (and GitHub, which also answers 405 for a non-mergeable pull request).
 */
public class InvalidStateException extends RuntimeException {
    public InvalidStateException(String message) {
        super(message);
    }
}
