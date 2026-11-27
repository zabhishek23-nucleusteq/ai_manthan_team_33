package com.aimanthan.expenseflow.api;

import java.util.Map;

/**
 * Error body for every non-2xx response: stable machine {@code code}, human {@code message},
 * optional {@code details} (e.g. field errors). Matches the error schema in contracts/openapi.yaml.
 */
public record ApiError(String code, String message, Map<String, Object> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Map.of());
    }
}
