package com.expenseflow.mockbank.payout;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory store of received payout files. Idempotent on the file's BATCH_ID (the business identity): the same
 * batch with the same content returns the original result (never a second payout); the same batch with other
 * content is a conflict. An optional {@code Idempotency-Key} header is bound to the first batch it was used with.
 */
@Service
public class PayoutFileService {

    private record Stored(String contentHash, PayoutFileResult result) {
    }

    /** Outcome of a submit: the result and whether it was replayed from an earlier request. */
    public record Submission(PayoutFileResult result, boolean replayed) {
    }

    private final Map<String, PayoutFileResult> files = new ConcurrentHashMap<>();
    private final Map<String, Stored> byBatch = new ConcurrentHashMap<>();
    private final Map<String, String> batchByKey = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock = Clock.systemUTC();

    public PayoutFileService(MeterRegistry meters) {
        this.meters = meters;
    }

    public Submission submit(String content, String idempotencyKey) {
        PayoutCsvParser.Result parsed;
        try {
            parsed = PayoutCsvParser.parse(content);
        } catch (InvalidPayoutFileException e) {
            meters.counter("mockbank_payout_files_total", "outcome", "invalid").increment();
            throw e;
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String boundBatch = batchByKey.putIfAbsent(idempotencyKey, parsed.batchId());
            if (boundBatch != null && !boundBatch.equals(parsed.batchId())) {
                conflict("Idempotency-Key already used for batch " + boundBatch);
            }
        }
        String hash = sha256(content);
        boolean[] created = {false};
        Stored stored = byBatch.computeIfAbsent(parsed.batchId(), b -> {
            created[0] = true;
            return new Stored(hash, store(parsed));
        });
        if (!stored.contentHash().equals(hash)) {
            conflict("BATCH_ID " + parsed.batchId() + " was already submitted with different content");
        }
        if (!created[0]) {
            meters.counter("mockbank_payout_files_total", "outcome", "replayed").increment();
        }
        return new Submission(stored.result(), !created[0]);
    }

    public Optional<PayoutFileResult> find(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    /** Reconciliation: what happened to a batch whose submit outcome is unknown (e.g. after a timeout)? */
    public Optional<PayoutFileResult> findByBatch(String batchId) {
        return Optional.ofNullable(byBatch.get(batchId)).map(Stored::result);
    }

    private void conflict(String message) {
        meters.counter("mockbank_payout_files_total", "outcome", "idempotency_conflict").increment();
        throw new IdempotencyConflictException(message);
    }

    private PayoutFileResult store(PayoutCsvParser.Result parsed) {
        int rejected = parsed.errors().size();
        String status = parsed.accepted() == 0 ? "REJECTED" : rejected == 0 ? "ACCEPTED" : "PARTIALLY_ACCEPTED";
        PayoutFileResult result = new PayoutFileResult(
                "PF-" + UUID.randomUUID(),
                parsed.batchId(),
                parsed.valueDate(),
                parsed.accepted(),
                rejected,
                parsed.acceptedAmount(),
                status,
                parsed.errors(),
                Instant.now(clock));
        files.put(result.fileId(), result);
        meters.counter("mockbank_payout_files_total", "outcome", status.toLowerCase()).increment();
        meters.counter("mockbank_payout_lines_total", "outcome", "accepted").increment(parsed.accepted());
        meters.counter("mockbank_payout_lines_total", "outcome", "rejected").increment(rejected);
        return result;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
