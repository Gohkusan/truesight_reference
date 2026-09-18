package com.truesight.backend.common;

import java.time.Instant;
import java.util.Map;

/**
 * One shape for every error response in the app. AC 10.1: "every failure names the
 * affected [thing] and the cause" — a consistent envelope means the frontend can render
 * every error banner the same way instead of special-casing each endpoint's failure
 * shape.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(Instant.now(), status, error, message, null);
    }

    public static ErrorResponse withFieldErrors(int status, String error, String message, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, message, fieldErrors);
    }
}
