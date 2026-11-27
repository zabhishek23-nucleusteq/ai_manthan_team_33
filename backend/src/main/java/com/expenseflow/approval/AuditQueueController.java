package com.expenseflow.approval;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.api.ClaimDtos.ClaimDto;
import com.expenseflow.api.ClaimMapper;
import com.expenseflow.api.CurrentUser;
import com.expenseflow.approval.ApprovalController.ApprovalTask;
import com.expenseflow.approval.ApprovalController.ApprovalTaskPage;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.idempotency.Idempotent;

/** FR-9 / FR-10 auditor queue. FINANCE_AUDITOR and FINANCE_HEAD only (also enforced on the URL in SecurityConfig). */
@RestController
@RequestMapping("/api/audit-queue")
@PreAuthorize("hasAnyRole('FINANCE_AUDITOR','FINANCE_HEAD')")
public class AuditQueueController {

    public record BulkApproveInput(@NotEmpty @Size(max = AuditQueueService.MAX_BULK) List<Long> claimIds,
            @Size(max = 2000) String comment) {
    }

    public record BulkApproveResult(List<AuditQueueService.BulkResult> results) {
    }

    public record SampleAuditInput(@NotBlank String result, @Size(max = 2000) String comment) {
    }

    private final AuditQueueService queue;
    private final ClaimMapper mapper;

    public AuditQueueController(AuditQueueService queue, ClaimMapper mapper) {
        this.queue = queue;
        this.mapper = mapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApprovalTaskPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long departmentId, @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minAmount, @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String violation, @RequestParam(defaultValue = "false") boolean sampleOnly,
            @RequestParam(required = false) Integer olderThanHours, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        AuditQueueService.QueuePage result = queue.queue(CurrentUser.of(jwt).id(), new AuditQueueService.Filters(
                departmentId, category, minAmount, maxAmount, violation, sampleOnly, olderThanHours, sort), p, s);
        Instant now = Instant.now();
        List<ApprovalTask> items = result.items().stream().map(i -> {
            Claim c = i.claim();
            Instant since = c.getSubmittedAt() != null ? c.getSubmittedAt() : now;
            return new ApprovalTask(c.getId(), c.getCode(), c.getTitle(), ClaimMapper.ref(c.getEmployee()),
                    ClaimMapper.dept(c.getEmployee().getDepartment()), c.getTotalAmount(), i.level(),
                    ClaimMapper.ref(i.onBehalfOf()), mapper.toSummary(c).violationCodes(),
                    c.getSampleAudit() == SampleAudit.PENDING, since,
                    Math.round(Duration.between(since, now).toMinutes() / 6.0) / 10.0);
        }).toList();
        return new ApprovalTaskPage(items, p, s, result.total());
    }

    @PostMapping("/bulk-approve")
    @Idempotent
    public BulkApproveResult bulkApprove(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BulkApproveInput in) {
        return new BulkApproveResult(queue.bulkApprove(CurrentUser.of(jwt).id(), in.claimIds(), in.comment()));
    }

    @PostMapping("/{claimId}/sample-audit")
    @Transactional
    public ClaimDto sampleAudit(@AuthenticationPrincipal Jwt jwt, @PathVariable long claimId,
            @Valid @RequestBody SampleAuditInput in) {
        return mapper.toDto(queue.sampleAudit(CurrentUser.of(jwt).id(), claimId, in.result(), in.comment()));
    }
}
