package com.expenseflow.api;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.api.ClaimDtos.UserRef;
import com.expenseflow.domain.PayoutBatch;
import com.expenseflow.idempotency.Idempotent;
import com.expenseflow.payout.PayoutService;

/** Payout batches (FR-11). rbac.md: read + export for FINANCE_AUDITOR and FINANCE_HEAD; create + submit FINANCE_HEAD. */
@RestController
@RequestMapping("/api/payout-batches")
public class PayoutController {

    public record PayoutItemDto(Long claimId, String claimCode, UserRef employee, BigDecimal amount) {
    }

    public record PayoutBatchDto(Long id, String code, String status, int itemCount, BigDecimal total, LocalDate valueDate,
                                 String bankReference, String lastError, Instant createdAt, Instant submittedAt, List<PayoutItemDto> items) {
    }

    public record PayoutBatchPage(List<PayoutBatchDto> items, int page, int size, long total) {
    }

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    static PayoutBatchDto toDto(PayoutBatch b, boolean withItems) {
        List<PayoutItemDto> items = !withItems ? null : b.getItems().stream()
                .map(i -> new PayoutItemDto(i.getClaim().getId(), i.getClaim().getCode(), ClaimMapper.ref(i.getClaim().getEmployee()), i.getAmount()))
                .toList();
        return new PayoutBatchDto(b.getId(), b.code(), b.getStatus().name(), b.getItemCount(), b.getTotal(), b.getValueDate(),
                b.getBankReference(), b.getLastError(), b.getCreatedAt(), b.getSubmittedAt(), items);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE_AUDITOR','FINANCE_HEAD')")
    public PayoutBatchPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        long[] total = {0};
        List<PayoutBatchDto> items = service.page(page, size, b -> toDto(b, false), total);
        return new PayoutBatchPage(items, page, size, total[0]);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE_AUDITOR','FINANCE_HEAD')")
    public PayoutBatchDto get(@PathVariable long id) {
        return service.read(id, b -> toDto(b, true));
    }

    @GetMapping(value = "/{id}/export.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('FINANCE_AUDITOR','FINANCE_HEAD')")
    public ResponseEntity<byte[]> export(@PathVariable long id) {
        String code = service.read(id, PayoutBatch::code);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(code + ".csv").build().toString())
                .body(service.csv(id).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasRole('FINANCE_HEAD')")
    public ResponseEntity<PayoutBatchDto> create(@AuthenticationPrincipal Jwt jwt) {
        Long id = service.create(CurrentUser.of(jwt).id());
        return ResponseEntity.created(URI.create("/api/payout-batches/" + id)).body(service.read(id, b -> toDto(b, true)));
    }

    /** 200 with the batch when the bank took the file; 502 BANK_UNAVAILABLE (batch SUBMIT_FAILED, retry allowed) otherwise. */
    @PostMapping("/{id}/submit")
    @Idempotent
    @PreAuthorize("hasRole('FINANCE_HEAD')")
    public PayoutBatchDto submit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        PayoutService.SubmitOutcome outcome = service.submit(id, CurrentUser.of(jwt).id());
        if (outcome.failure() != null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, ErrorCodes.BANK_UNAVAILABLE,
                    "The bank did not take the file; the batch is SUBMIT_FAILED and can be retried. " + outcome.failure(), null);
        }
        return service.read(id, b -> toDto(b, true));
    }
}
