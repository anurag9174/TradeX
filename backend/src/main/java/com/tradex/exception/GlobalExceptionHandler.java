package com.tradex.exception;

import com.tradex.dto.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure into the same JSON envelope, so the frontend has exactly one error shape to
 * handle: {@code {timestamp, status, code, message, path, fieldErrors?}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getObjectName(),
                        error.getDefaultMessage() == null ? "Invalid request" : error.getDefaultMessage()));
        String message = fieldErrors.isEmpty()
                ? "Request validation failed."
                : "Request validation failed: " + String.join("; ", fieldErrors.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()).toList());
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED", message, request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.putIfAbsent(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED", "Request validation failed.", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        String detail = ex.getMostSpecificCause() == null ? "Malformed JSON" : ex.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST", "Could not read the request body: " + detail,
                request.getRequestURI()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParameter(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST.value(),
                "INVALID_PARAMETER", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST.value(),
                "BAD_REQUEST", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT.value(),
                "CONFLICT", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHORIZED", "Authentication failed.", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN", "You do not have permission to perform this action.", request.getRequestURI()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        log.warn("Optimistic lock conflict at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT.value(),
                "CONCURRENT_MODIFICATION",
                "The record was modified by another request. Please retry.", request.getRequestURI()));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handlePessimisticLock(PessimisticLockingFailureException ex,
                                                          HttpServletRequest request) {
        log.warn("Lock timeout at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT.value(),
                "TRADE_IN_PROGRESS",
                "Another trade on this account is still being processed. Please retry.", request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        log.warn("Data integrity violation at {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(HttpStatus.CONFLICT.value(),
                "DATA_INTEGRITY",
                "The request conflicted with existing data (a duplicate order reference or execution).",
                request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(), "METHOD_NOT_ALLOWED",
                ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND", "No endpoint matches " + request.getRequestURI(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "An unexpected server error occurred. The operation was rolled back.", request.getRequestURI()));
    }
}
