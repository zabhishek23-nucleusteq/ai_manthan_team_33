package com.expenseflow.api;

/**
 * Stable error codes from contracts/openapi.yaml (info.description). Use these constants instead of string
 * literals so the codes the frontend switches on never drift. Adding a code = contract change first.
 */
public final class ErrorCodes {

    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";                 // 401
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";         // 401
    public static final String FORBIDDEN = "FORBIDDEN";                             // 403
    public static final String NOT_ASSIGNED_APPROVER = "NOT_ASSIGNED_APPROVER";     // 403
    public static final String SELF_APPROVAL_FORBIDDEN = "SELF_APPROVAL_FORBIDDEN"; // 403
    public static final String NOT_FOUND = "NOT_FOUND";                             // 404
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";             // 400
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";             // 400
    public static final String INVALID_RANGE = "INVALID_RANGE";                     // 400
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";                         // 409
    public static final String ILLEGAL_STATE_TRANSITION = "ILLEGAL_STATE_TRANSITION"; // 409
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION"; // 409
    public static final String CANNOT_REMOVE_OWN_ADMIN = "CANNOT_REMOVE_OWN_ADMIN"; // 409
    public static final String RECEIPT_TOO_LARGE = "RECEIPT_TOO_LARGE";             // 413
    public static final String UNSUPPORTED_FILE_TYPE = "UNSUPPORTED_FILE_TYPE";     // 415
    public static final String CLAIM_EMPTY = "CLAIM_EMPTY";                         // 422
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";   // 422
    public static final String DELEGATION_INVALID = "DELEGATION_INVALID";           // 422
    public static final String NO_ELIGIBLE_CLAIMS = "NO_ELIGIBLE_CLAIMS";           // 422
    public static final String BANK_UNAVAILABLE = "BANK_UNAVAILABLE";               // 502
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";                   // 500

    private ErrorCodes() {
    }
}
