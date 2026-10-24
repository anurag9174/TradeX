package com.tradex.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for every business failure the API reports.
 *
 * <p>Carries a stable machine-readable {@code code} alongside the HTTP status so the frontend
 * can branch on the reason without parsing prose.</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
