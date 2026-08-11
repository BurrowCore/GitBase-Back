package org.os.gitbase.exception;

/**
 * Thrown when an authenticated user attempts an operation on a resource they
 * do not own or lack permission for. Mapped to HTTP 403 by {@link GlobalExceptionHandler}.
 */
public class AccessDeniedDomainException extends RuntimeException {
    public AccessDeniedDomainException(String message) {
        super(message);
    }
}
