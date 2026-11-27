package com.expenseflow.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a state-changing controller method as replay-safe (NFR-IDEM). Put it on every POST listed in NFR-IDEM:
 * submit, approve, reject, send-back, bulk-approve, payout create/submit.
 * <pre>
 * &#64;PostMapping("/api/claims/{id}/submit")
 * &#64;Idempotent
 * ClaimDto submit(&#64;PathVariable Long id) { ... }
 * </pre>
 * {@link IdempotencyFilter} then requires an {@code Idempotency-Key} header (8-64 chars, 400 VALIDATION_FAILED
 * if missing), and per user + key: same method/path/body replays the stored response (header
 * {@code Idempotent-Replayed: true}) without running the method again; another body is 422
 * IDEMPOTENCY_KEY_REUSED; a request still running with that key is 409 CONCURRENT_MODIFICATION.
 * 5xx responses are not stored, so the client can retry with the same key.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    String HEADER = "Idempotency-Key";
    String REPLAYED_HEADER = "Idempotent-Replayed";
}
