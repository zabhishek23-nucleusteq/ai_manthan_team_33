package com.expenseflow.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to {@link ApiError}: validation 400, access denied 403, not found 404,
 * conflict / illegal transition 409, business refusal 422, anything else 500 (no internals leaked).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "Request has invalid fields", Map.of("fields", fields)));
    }

    /** {@code ?page=abc}, {@code ?from=yesterday}: wrong type for a query/path parameter (T-603). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        String type = ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest().body(new ApiError(ErrorCodes.VALIDATION_FAILED,
                "Parameter " + ex.getName() + " must be a valid " + type,
                Map.of("fields", Map.of(ex.getName(), "must be a valid " + type))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCodes.VALIDATION_FAILED,
                "Required parameter " + ex.getParameterName() + " is missing",
                Map.of("fields", Map.of(ex.getParameterName(), "is required"))));
    }

    /** Constraint annotations on @RequestParam / @PathVariable (e.g. {@code @Min(0) int page}). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors().forEach(e -> fields.putIfAbsent(name == null ? "param" : name, e.getDefaultMessage()));
        });
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCodes.VALIDATION_FAILED, "Request has invalid parameters", Map.of("fields", fields)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST", "Request body could not be read"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCodes.VALIDATION_FAILED,
                "Required header " + ex.getHeaderName() + " is missing", Map.of("header", ex.getHeaderName())));
    }

    /** NFR-CONC: a stale @Version (two approvers, two tabs) is a 409 the client can recover from by reloading. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrent(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(ErrorCodes.CONCURRENT_MODIFICATION,
                "Someone else changed this record. Reload and try again."));
    }

    /** Files above the multipart limit never reach ReceiptService; same answer as its 5 MB check (BR-20). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiError.of(ErrorCodes.RECEIPT_TOO_LARGE, "Receipts can be at most 5 MB"));
    }

    /** Missing multipart file / not a multipart request: client errors, not 500 (missing query params: missingParam above). */
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<ApiError> missingPart(Exception ex) {
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCodes.VALIDATION_FAILED,
                "Required request part or parameter is missing (multipart field 'file' for receipts)"));
    }

    /** Wrong Content-Type for the endpoint (e.g. JSON sent to the multipart receipt upload): 415, not 500. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiError.of(ErrorCodes.MALFORMED_REQUEST,
                "Content-Type " + ex.getContentType() + " is not supported here"));
    }

    /** Wrong HTTP method on an existing path: 405, not 500. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of(ErrorCodes.MALFORMED_REQUEST,
                "Method " + ex.getMethod() + " is not allowed here"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "You do not have access to this resource"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("INTERNAL_ERROR", "Something went wrong"));
    }
}
