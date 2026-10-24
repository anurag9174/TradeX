package com.tradex.dto.common;

import java.time.Instant;
import java.util.Map;

/** Consistent error envelope returned by every failing endpoint. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null);
    }

    public static ApiError of(int status, String code, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, code, message, path, fieldErrors);
    }
}
