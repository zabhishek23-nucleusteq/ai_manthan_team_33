package com.expenseflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Shared error mapping added in T-018 (NFR-ERR, NFR-CONC). Plain unit test, no Spring context. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void staleVersion_is409ConcurrentModification() {
        ResponseEntity<ApiError> res = handler.concurrent(new ObjectOptimisticLockingFailureException("Claim", 1L));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo(ErrorCodes.CONCURRENT_MODIFICATION);
    }

    @Test
    void apiExceptionKeepsStatusCodeAndDetails() {
        ResponseEntity<ApiError> res = handler.api(ApiException.badRequest(ErrorCodes.INVALID_RANGE, "bad", java.util.Map.of("from", "x")));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().code()).isEqualTo("INVALID_RANGE");
        assertThat(res.getBody().details()).containsEntry("from", "x");
    }
}
