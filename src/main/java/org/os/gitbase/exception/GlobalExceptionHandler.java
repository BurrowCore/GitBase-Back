package org.os.gitbase.exception;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.common.ApiResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralizes exception → HTTP mapping for every REST controller.
 *
 * <p>Every error is returned in the canonical {@link ApiResponseEntity} envelope so the
 * frontend can rely on a single error shape: {@code {success:false, error, message, httpStatus}}.
 *
 * <p>The Git smart-HTTP endpoints ({@code /gitbase/**}, served by the JGit {@code GitServlet})
 * deliberately bypass this handler — they stream raw pkt-line protocol data and manage their own
 * status codes.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Bean validation failures on {@code @Valid @RequestBody} → 400 with field details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.debug("Validation failure: {}", details);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details.isBlank() ? "Validation failed" : details);
    }

    /** Invalid arguments / malformed input surfaced from the service layer → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    /** Resource lookup miss → 404. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.debug("Not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /** Ownership / permission violations → 403. */
    @ExceptionHandler(AccessDeniedDomainException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleAccessDenied(AccessDeniedDomainException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    /**
     * State collisions → 409. Merge conflicts carry the conflicting paths in {@code data} so the
     * UI can list exactly which files need attention.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponseEntity<List<String>>> handleConflict(ConflictException ex) {
        log.debug("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponseEntity<>(
                Instant.now(), false, ex.getMessage(), HttpStatus.CONFLICT, "CONFLICT", ex.getConflicts()));
    }

    /** Operation not allowed for the resource's current lifecycle state → 405. */
    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleInvalidState(InvalidStateException ex) {
        log.debug("Invalid state: {}", ex.getMessage());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "INVALID_STATE", ex.getMessage());
    }

    /** Path-traversal / security guard trips → 403, with a generic message (no internals leaked). */
    /** A concurrent update won the race (JPA {@code @Version}) — the client should reload and retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.debug("Concurrent modification: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", "This item was changed by someone else. Reload and try again.");
    }

    /** The request carried no usable identity (e.g. a principal with no matching account). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleAuthentication(AuthenticationException ex) {
        log.debug("Unauthenticated: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleSecurity(SecurityException ex) {
        log.warn("Security exception: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Operation not permitted");
    }

    /** Catch-all → 500, without exposing the stack trace or internal message to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiResponseEntity<Void>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(ApiResponseEntity.failure(status, error, message));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }
}
