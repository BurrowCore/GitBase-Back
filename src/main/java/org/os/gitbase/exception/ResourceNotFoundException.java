package org.os.gitbase.exception;

/**
 * Thrown when a requested resource (repository, token, branch, etc.) does not exist.
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
