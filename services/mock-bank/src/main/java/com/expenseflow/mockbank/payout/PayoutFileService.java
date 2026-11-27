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
 * In-memory store of received payout files. Idempotent per {@code Idempotency-Key}: the same key with the
 * same content returns the original result (never a second payout); the same key with other content is a
 * conflict.
 */
@Service
public class PayoutFileService {

    private record Stored(String contentHash, PayoutFileResult result) {
    }

    /** Outcome of a submit: the result and whether it was replayed from an earlier request. */
    public record Submission(PayoutFileResult result, boolean replayed) {
    }

    private final Map<String, PayoutFileResult> files = new ConcurrentHashMap<>();
    private final Map<String, Stored> byKey = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock = Clock.systemUTC();

    public PayoutFileService(MeterRegistry meters) {
        this.meters = meters;
    }

    public Submission submit(String content, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Submission(process(content), false);
        }
        String hash = sha256(content == null ? "" : content);
        boolean[] created = {false};
        Stored stored = byKey.computeIfAbsent(idempotencyKey, k -> {
            created[0] = true;
            return new Stored(hash, process(content));
        });
        if (!stored.contentHash().equals(hash)) {
            meters.counter("mockbank_payout_files_total", "outcome", "idempotency_conflict").increment();
            throw new IdempotencyConflictException("Idempotency-Key already used with a different file");
        }
        if (!created[0]) {
            meters.counter("mockbank_payout_files_total", "outcome", "replayed").increment();
        }
        return new Submission(stored.result(), !created[0]);
    }

    public Optional<PayoutFileResult> find(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    private PayoutFileResult process(String content) {
        PayoutCsvParser.Result parsed;
        try {
            parsed = PayoutCsvParser.parse(content);
        } catch (InvalidPayoutFileException e) {
            meters.counter("mockbank_payout_files_total", "outcome", "invalid").increment();
            throw e;
        }
        int rejected = parsed.errors().size();
        String status = parsed.accepted() == 0 ? "REJECTED" : rejected == 0 ? "ACCEPTED" : "PARTIALLY_ACCEPTED";
        PayoutFileResult result = new PayoutFileResult(
                "PF-" + UUID.randomUUID(),
                parsed.accepted(),
                rejected,
                parsed.acceptedAmountMinor(),
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
