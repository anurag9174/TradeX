package com.tradex.exception;

import org.springframework.http.HttpStatus;

/** Raised when a client exceeds the configured request rate for a sensitive endpoint. */
public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }
}
