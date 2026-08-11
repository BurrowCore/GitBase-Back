package org.os.gitbase.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * Canonical API response envelope for every REST endpoint in GitBase.
 *
 * <p>Serialized JSON shape (single source of truth — mirrored in
 * {@code backend/docs/openapi.yaml} and the frontend {@code ApiResponse<T>} type):
 *
 * <pre>
 * {
 *   "timestamp": "2026-06-24T10:00:00Z",
 *   "success":   true,
 *   "message":   "Repository created",
 *   "httpStatus":"CREATED",
 *   "error":     null,
 *   "data":      { ... }
 * }
 * </pre>
 *
 * <p>Controllers should prefer the static factory methods ({@link #ok},
 * {@link #created}, {@link #message}, {@link #failure}) over the constructor.
 *
 * @param <T> type of the {@code data} payload
 */
@Getter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponseEntity<T> {

    private final Instant timestamp;
    private final boolean success;
    private final String message;
    private final HttpStatus httpStatus;
    private final String error;
    private final T data;

    /**
     * Canonical constructor. Field order matches the legacy
     * {@code (Instant, boolean, String, HttpStatus, T)} call sites.
     */
    public ApiResponseEntity(Instant timestamp, boolean success, String message, HttpStatus httpStatus, T data) {
        this(timestamp, success, message, httpStatus, null, data);
    }

    public ApiResponseEntity(Instant timestamp, boolean success, String message,
                             HttpStatus httpStatus, String error, T data) {
        this.timestamp = timestamp;
        this.success = success;
        this.message = message;
        this.httpStatus = httpStatus;
        this.error = error;
        this.data = data;
    }

    // ─── Success factories ────────────────────────────────────────────────

    /** 200 OK with a payload. */
    public static <T> ApiResponseEntity<T> ok(T data, String message) {
        return new ApiResponseEntity<>(Instant.now(), true, message, HttpStatus.OK, null, data);
    }

    /** 201 Created with a payload. */
    public static <T> ApiResponseEntity<T> created(T data, String message) {
        return new ApiResponseEntity<>(Instant.now(), true, message, HttpStatus.CREATED, null, data);
    }

    /** Success with a message and no payload (e.g. 204-style results returned as 200). */
    public static ApiResponseEntity<Void> message(String message, HttpStatus status) {
        return new ApiResponseEntity<>(Instant.now(), true, message, status, null, null);
    }

    // ─── Error factory ────────────────────────────────────────────────────

    /** Error envelope used by the global exception handler. */
    public static <T> ApiResponseEntity<T> failure(HttpStatus status, String error, String message) {
        return new ApiResponseEntity<>(Instant.now(), false, message, status, error, null);
    }
}
