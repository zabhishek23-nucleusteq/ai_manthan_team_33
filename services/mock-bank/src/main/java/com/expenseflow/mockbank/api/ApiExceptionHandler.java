package com.expenseflow.mockbank.api;

import com.expenseflow.mockbank.fault.InjectedFaultException;
import com.expenseflow.mockbank.payout.IdempotencyConflictException;
import com.expenseflow.mockbank.payout.InvalidPayoutFileException;
import com.expenseflow.mockbank.payout.PayoutFileNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Uniform JSON error body: {code, message, at}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String code, String message, Instant at) {
    }

    @ExceptionHandler(InjectedFaultException.class)
    ResponseEntity<ApiError> fault(InjectedFaultException e) {
        return error(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
    }

    @ExceptionHandler(InvalidPayoutFileException.class)
    ResponseEntity<ApiError> invalidFile(InvalidPayoutFileException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_FILE", e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_FILE", "Multipart field 'file' is required");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> conflict(IdempotencyConflictException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed request body (mode must be ok|500|timeout)");
    }

    @ExceptionHandler(PayoutFileNotFoundException.class)
    ResponseEntity<ApiError> notFound(PayoutFileNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}
