package com.expenseflow.idempotency;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.audit.AuditService;

/**
 * Test-only endpoints (src/test, never shipped) standing in for real @Idempotent POSTs such as submit/approve
 * until T-016/T-019 exist. Counts executions per {@code ref} so tests can prove a replay did not run the handler.
 */
@RestController
class IdempotencyProbeController {

    record ProbeBody(String ref, Long claimId, String note) {
    }

    static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED_ONCE = ConcurrentHashMap.newKeySet();

    private final AuditService audit;

    IdempotencyProbeController(AuditService audit) {
        this.audit = audit;
    }

    static int executions(String ref) {
        AtomicInteger n = EXECUTIONS.get(ref);
        return n == null ? 0 : n.get();
    }

    /** Like a claim submit: changes state, writes one audit event in the same transaction, returns 201 + body. */
    @PostMapping("/api/test-probe/idempotent")
    @Idempotent
    @Transactional
    ResponseEntity<Map<String, Object>> idempotent(@RequestBody ProbeBody body) {
        int n = EXECUTIONS.computeIfAbsent(body.ref(), r -> new AtomicInteger()).incrementAndGet();
        if (body.claimId() != null) {
            audit.record("PROBE_SUBMITTED", AuditService.CLAIM, body.claimId(), Map.of("status", "DRAFT"),
                    Map.of("status", "PENDING_MANAGER", "note", String.valueOf(body.note())));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("ref", body.ref(), "execution", n, "note", String.valueOf(body.note())));
    }

    /** First call for a ref fails with 500 (key must be released), later calls succeed. */
    @PostMapping("/api/test-probe/idempotent-fail-once")
    @Idempotent
    Map<String, Object> failOnce(@RequestBody ProbeBody body) {
        int n = EXECUTIONS.computeIfAbsent(body.ref(), r -> new AtomicInteger()).incrementAndGet();
        if (FAILED_ONCE.add(body.ref())) {
            throw new IllegalStateException("simulated crash");
        }
        return Map.of("ref", body.ref(), "execution", n);
    }

    /** Not annotated: no key needed, never stored. */
    @PostMapping("/api/test-probe/not-idempotent")
    Map<String, Object> plain(@RequestBody ProbeBody body) {
        int n = EXECUTIONS.computeIfAbsent(body.ref(), r -> new AtomicInteger()).incrementAndGet();
        return Map.of("ref", body.ref(), "execution", n);
    }
}
