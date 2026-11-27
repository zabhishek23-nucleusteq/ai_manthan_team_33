package com.expenseflow.payout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.AuditActions;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.PayoutBatch;
import com.expenseflow.domain.PayoutBatchStatus;
import com.expenseflow.domain.PayoutItem;
import com.expenseflow.domain.User;
import com.expenseflow.integration.MockBankClient;
import com.expenseflow.integration.MockBankClient.BankException;
import com.expenseflow.integration.MockBankClient.BankResult;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.PayoutBatchRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.service.ClaimEvents.ClaimChanged;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Payout batches (FR-11, BR-17, BR-18). Submit runs in three steps so a bank failure is recorded and a retry can
 * never pay twice: (1) lock the batch and mark SUBMITTED, commit; (2) call the bank outside any transaction with a
 * per-batch Idempotency-Key (the bank replays its stored result for the same key); (3) apply the result, commit.
 */
@Service
public class PayoutService {

    static final String CSV_HEADER = "RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION";
    private static final Set<PayoutBatchStatus> SUBMITTABLE = Set.of(PayoutBatchStatus.CREATED, PayoutBatchStatus.SUBMIT_FAILED);

    private final PayoutBatchRepository batches;
    private final ClaimRepository claims;
    private final UserRepository users;
    private final MockBankClient bank;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public PayoutService(PayoutBatchRepository batches, ClaimRepository claims, UserRepository users, MockBankClient bank,
                         ApplicationEventPublisher events, MeterRegistry meters, PlatformTransactionManager txm) {
        this.batches = batches;
        this.claims = claims;
        this.users = users;
        this.bank = bank;
        this.events = events;
        this.meters = meters;
        this.tx = new TransactionTemplate(txm);
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCodes.NOT_FOUND, "Payout batch not found");
    }

    // ---------------------------------------------------------------- create
    /** Takes every payable claim (BR-18) into a new batch; claims move to IN_PAYOUT. 422 when nothing is payable. */
    public Long create(long actorId) {
        return tx.execute(s -> {
            List<Long> ids = claims.lockPayableClaimIds();
            if (ids.isEmpty()) {
                throw ApiException.refused(ErrorCodes.NO_ELIGIBLE_CLAIMS, "No approved claims are waiting for payout", null);
            }
            User actor = users.findById(actorId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
            PayoutBatch b = new PayoutBatch(actor, LocalDate.now(ZoneOffset.UTC));
            for (Claim c : claims.findAllById(ids)) {
                b.addClaim(c);
                c.setStatus(ClaimStatus.IN_PAYOUT);
            }
            batches.saveAndFlush(b);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "CREATED");
            after.put("items", b.getItemCount());
            after.put("total", b.getTotal());
            after.put("claimIds", ids);
            events.publishEvent(new BatchChanged(AuditActions.PAYOUT_CREATED, b.getId(), actorId, null, after));
            return b.getId();
        });
    }

    // ---------------------------------------------------------------- read
    public <T> T read(long id, java.util.function.Function<PayoutBatch, T> mapper) {
        return readTx.execute(s -> mapper.apply(batches.findById(id).orElseThrow(PayoutService::notFound)));
    }

    public <T> List<T> page(int page, int size, java.util.function.Function<PayoutBatch, T> mapper, long[] totalOut) {
        return readTx.execute(s -> {
            Page<PayoutBatch> p = batches.findAllBy(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                    Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            totalOut[0] = p.getTotalElements();
            return p.getContent().stream().map(mapper).toList();
        });
    }

    /** BR-17 bank file: header, one D row per claim, T trailer with count and total. UTF-8, comma separated. */
    public String csv(long id) {
        return readTx.execute(s -> csv(batches.findById(id).orElseThrow(PayoutService::notFound)));
    }

    static String csv(PayoutBatch b) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        String code = b.code();
        String date = b.getValueDate().toString();
        for (PayoutItem i : b.getItems().stream().sorted((x, y) -> x.getClaim().getId().compareTo(y.getClaim().getId())).toList()) {
            Claim c = i.getClaim();
            User e = c.getEmployee();
            sb.append(String.join(",", "D", code, date, field(e.getEmployeeCode()), field(e.getName()), field(e.getBankAccountNo()),
                    field(e.getIfsc()), amount(i.getAmount()), "INR", field(c.getCode()))).append('\n');
        }
        sb.append(String.join(",", "T", code, date, Integer.toString(b.getItemCount()), amount(b.getTotal()))).append('\n');
        return sb.toString();
    }

    private static String amount(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** RFC 4180 quoting only when needed (names may contain commas). */
    private static String field(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    // ---------------------------------------------------------------- submit
    /** Result of a submit; {@code failure} set when the bank could not take the file (API answers 502). */
    public record SubmitOutcome(Long batchId, String failure) {
    }

    public SubmitOutcome submit(long batchId, long actorId) {
        // (1) lock, check state, mark SUBMITTED, build the file - committed before calling the bank
        String csv = tx.execute(s -> {
            PayoutBatch b = batches.findByIdForUpdate(batchId).orElseThrow(PayoutService::notFound);
            if (!SUBMITTABLE.contains(b.getStatus())) {
                throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "Batch " + b.code() + " is " + b.getStatus() + " and cannot be submitted");
            }
            PayoutBatchStatus before = b.getStatus();
            b.setStatus(PayoutBatchStatus.SUBMITTED);
            b.setSubmittedAt(Instant.now());
            batches.saveAndFlush(b);
            events.publishEvent(new BatchChanged(AuditActions.PAYOUT_SUBMITTED, b.getId(), actorId, Map.of("status", before.name()),
                    Map.of("status", "SUBMITTED", "items", b.getItemCount(), "total", b.getTotal())));
            return csv(b);
        });

        // (2) bank call outside any transaction; same key for every attempt of this batch -> bank pays once
        BankResult result;
        try {
            result = bank.submit(csv, "payout-batch-" + batchId);
        } catch (BankException e) {
            meters.counter("expenseflow_bank_submit_total", "result", e.kind()).increment();
            tx.executeWithoutResult(s -> {
                PayoutBatch b = batches.findByIdForUpdate(batchId).orElseThrow(PayoutService::notFound);
                b.setStatus(PayoutBatchStatus.SUBMIT_FAILED);
                b.setLastError(e.getMessage());
                batches.saveAndFlush(b);
                events.publishEvent(new BatchChanged("PAYOUT_SUBMIT_FAILED", batchId, null, Map.of("status", "SUBMITTED"),
                        Map.of("status", "SUBMIT_FAILED", "error", e.getMessage())));
            });
            return new SubmitOutcome(batchId, e.getMessage());
        }

        // (3) apply the bank's answer
        String status = result.status() == null ? "REJECTED" : result.status();
        meters.counter("expenseflow_bank_submit_total", "result", status.toLowerCase()).increment();
        BankResult r = result;
        return tx.execute(s -> {
            PayoutBatch b = batches.findByIdForUpdate(batchId).orElseThrow(PayoutService::notFound);
            b.setBankFileId(r.fileId());
            if ("REJECTED".equals(status)) {
                b.setStatus(PayoutBatchStatus.SUBMIT_FAILED);
                b.setLastError("Bank rejected the file: " + describe(r));
                batches.saveAndFlush(b);
                events.publishEvent(new BatchChanged("PAYOUT_SUBMIT_FAILED", batchId, null, Map.of("status", "SUBMITTED"),
                        Map.of("status", "SUBMIT_FAILED", "bankStatus", status)));
                return new SubmitOutcome(batchId, b.getLastError());
            }
            // ACCEPTED or PARTIALLY_ACCEPTED: rejected rows (reference = claim code) go back to APPROVED for a later batch
            Set<String> rejectedCodes = r.errors() == null ? Set.of()
                    : r.errors().stream().map(MockBankClient.BankResult.RowError::reference).collect(Collectors.toSet());
            Instant now = Instant.now();
            for (PayoutItem i : List.copyOf(b.getItems())) {
                Claim c = i.getClaim();
                if (rejectedCodes.contains(c.getCode())) {
                    c.setStatus(ClaimStatus.APPROVED);
                    b.removeItemFor(c.getId());
                } else if (c.getStatus() != ClaimStatus.PAID) {
                    c.setStatus(ClaimStatus.PAID);
                    c.setPaidAt(now);
                    events.publishEvent(new ClaimChanged(AuditActions.CLAIM_PAID, c.getId(), null, Map.of("status", "IN_PAYOUT"),
                            Map.of("status", "PAID", "batch", b.code())));
                }
            }
            b.setStatus(PayoutBatchStatus.PAID);
            b.setBankReference(r.fileId());
            b.setLastError(rejectedCodes.isEmpty() ? null : "Bank rejected " + rejectedCodes.size() + " row(s): " + describe(r));
            batches.saveAndFlush(b);
            events.publishEvent(new BatchChanged("PAYOUT_PAID", batchId, null, Map.of("status", "SUBMITTED"),
                    Map.of("status", "PAID", "bankStatus", status, "accepted", r.accepted(), "rejected", r.rejected())));
            return new SubmitOutcome(batchId, null);
        });
    }

    private static String describe(BankResult r) {
        if (r.errors() == null || r.errors().isEmpty()) return r.status();
        return r.errors().stream().limit(10).map(e -> e.reference() + " (" + e.reason() + ")").collect(Collectors.joining("; "));
    }

    /** Audited payout batch change (entity PAYOUT_BATCH). */
    public record BatchChanged(String action, Long batchId, Long actorId, Map<String, Object> before, Map<String, Object> after)
            implements com.expenseflow.audit.AuditableChange {
        @Override public String entityType() { return com.expenseflow.audit.AuditService.PAYOUT_BATCH; }
        @Override public Object entityId() { return batchId; }
    }

    static ApiException bankUnavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.BANK_UNAVAILABLE, message, null);
    }
}
