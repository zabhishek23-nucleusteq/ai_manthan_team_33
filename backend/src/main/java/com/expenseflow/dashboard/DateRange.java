package com.expenseflow.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;

/**
 * A validated {@code [from, to)} range for dashboard / admin endpoints (NFR-DASH): {@code from} inclusive,
 * {@code to} exclusive, {@code from < to}, at most 366 days, else 400 {@code INVALID_RANGE}.
 */
public record DateRange(Instant from, Instant to) {

    public static final Duration MAX = Duration.ofDays(366);

    /** Parses ISO-8601 instants from query parameters; missing or malformed values are 400 INVALID_RANGE. */
    public static DateRange parse(String from, String to) {
        return of(parseInstant(from, from, to), parseInstant(to, from, to));
    }

    private static Instant parseInstant(String value, String from, String to) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_RANGE, "from and to are required",
                    Map.of("from", String.valueOf(from), "to", String.valueOf(to)));
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_RANGE,
                    "from/to must be ISO-8601 instants, e.g. 2026-11-20T00:00:00Z",
                    Map.of("from", String.valueOf(from), "to", String.valueOf(to)));
        }
    }

    public static DateRange of(Instant from, Instant to) {
        if (from == null || to == null) {
            throw invalid("from and to are required", from, to);
        }
        if (!from.isBefore(to)) {
            throw invalid("from must be before to", from, to);
        }
        if (Duration.between(from, to).compareTo(MAX) > 0) {
            throw invalid("range must not exceed 366 days", from, to);
        }
        return new DateRange(from, to);
    }

    private static ApiException invalid(String message, Instant from, Instant to) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_RANGE, message,
                Map.of("from", String.valueOf(from), "to", String.valueOf(to)));
    }
}
