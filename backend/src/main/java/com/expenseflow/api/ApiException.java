package com.expenseflow.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Throw from services for expected failures. Use the factory methods so status codes stay consistent:
 * {@link #notFound} 404, {@link #conflict} 409 (illegal transition, duplicate), {@link #refused} 422 (business rule).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, null);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, null);
    }

    public static ApiException refused(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, details);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
