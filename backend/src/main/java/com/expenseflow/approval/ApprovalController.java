package com.expenseflow.approval;

import com.expenseflow.api.ClaimDtos.ClaimDto;
import com.expenseflow.api.ClaimDtos.DepartmentDto;
import com.expenseflow.api.ClaimDtos.UserRef;
import com.expenseflow.api.ClaimMapper;
import com.expenseflow.api.CurrentUser;
import com.expenseflow.domain.ApprovalDecision;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.Delegation;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Approvals, delegations and the delegate picker (FR-6..FR-8). Roles per rbac.md section 3; row rules in the services. */
@RestController
@Validated
public class ApprovalController {

    public record DecisionInput(@Size(max = 2000) String comment) {
    }

    public record DecisionInputRequired(@NotBlank @Size(min = 3, max = 2000) String comment) {
    }

    public record DelegationInput(@NotNull Long delegateId, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record DelegationDto(Long id, UserRef delegator, UserRef delegate, LocalDate from, LocalDate to, boolean active) {
    }

    public record ApprovalTask(Long claimId, String code, String title, UserRef employee, DepartmentDto department, BigDecimal total,
                               ApprovalLevel level, UserRef onBehalfOf, List<String> violationCodes, boolean sampleAudit,
                               Instant submittedAt, double ageHours) {
    }

    public record ApprovalTaskPage(List<ApprovalTask> items, int page, int size, long total) {
    }

    private final ApprovalService approvals;
    private final DelegationService delegationService;
    private final ApproverDirectory directory;
    private final ClaimMapper mapper;

    public ApprovalController(ApprovalService approvals, DelegationService delegationService, ApproverDirectory directory, ClaimMapper mapper) {
        this.approvals = approvals;
        this.delegationService = delegationService;
        this.directory = directory;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- approvals
    @GetMapping("/api/approvals/inbox")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Transactional(readOnly = true)
    public ApprovalTaskPage inbox(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        List<ApprovalService.InboxItem> all = approvals.inbox(CurrentUser.of(jwt).id());
        Instant now = Instant.now();
        List<ApprovalTask> items = all.stream().skip((long) p * s).limit(s).map(i -> {
            Claim c = i.step().getClaim();
            Instant since = c.getSubmittedAt() != null ? c.getSubmittedAt() : i.step().getCreatedAt();
            return new ApprovalTask(c.getId(), c.getCode(), c.getTitle(), ClaimMapper.ref(c.getEmployee()),
                    ClaimMapper.dept(c.getEmployee().getDepartment()), c.getTotalAmount(), i.step().getLevel(),
                    ClaimMapper.ref(i.onBehalfOf()), mapper.toSummary(c).violationCodes(),
                    c.getSampleAudit() == SampleAudit.PENDING, since,
                    Math.round(Duration.between(since, now).toMinutes() / 6.0) / 10.0);
        }).toList();
        return new ApprovalTaskPage(items, p, s, all.size());
    }

    @PostMapping("/api/approvals/{claimId}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Idempotent
    @Transactional
    public ClaimDto approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long claimId,
                            @Valid @RequestBody(required = false) DecisionInput in) {
        String comment = in == null || in.comment() == null || in.comment().isBlank() ? null : in.comment().trim();
        return mapper.toDto(approvals.decide(CurrentUser.of(jwt).id(), claimId, ApprovalDecision.APPROVED, comment));
    }

    @PostMapping("/api/approvals/{claimId}/reject")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Idempotent
    @Transactional
    public ClaimDto reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long claimId, @Valid @RequestBody DecisionInputRequired in) {
        return mapper.toDto(approvals.decide(CurrentUser.of(jwt).id(), claimId, ApprovalDecision.REJECTED, in.comment().trim()));
    }

    @PostMapping("/api/approvals/{claimId}/send-back")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Idempotent
    @Transactional
    public ClaimDto sendBack(@AuthenticationPrincipal Jwt jwt, @PathVariable long claimId, @Valid @RequestBody DecisionInputRequired in) {
        return mapper.toDto(approvals.decide(CurrentUser.of(jwt).id(), claimId, ApprovalDecision.SENT_BACK, in.comment().trim()));
    }

    // ---------------------------------------------------------------- delegations
    private static DelegationDto dto(Delegation d) {
        return new DelegationDto(d.getId(), ClaimMapper.ref(d.getDelegator()), ClaimMapper.ref(d.getDelegate()),
                d.getFromDate(), d.getToDate(), d.isActive());
    }

    @GetMapping("/api/delegations")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Transactional(readOnly = true)
    public List<DelegationDto> delegations(@AuthenticationPrincipal Jwt jwt) {
        return delegationService.mine(CurrentUser.of(jwt).id()).stream().map(ApprovalController::dto).toList();
    }

    @PostMapping("/api/delegations")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @Idempotent
    @Transactional
    public ResponseEntity<DelegationDto> delegate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DelegationInput in) {
        Delegation d = delegationService.create(CurrentUser.of(jwt).id(), in.delegateId(), in.from(), in.to());
        return ResponseEntity.created(URI.create("/api/delegations/" + d.getId())).body(dto(d));
    }

    @DeleteMapping("/api/delegations/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        delegationService.revoke(CurrentUser.of(jwt).id(), id);
    }

    // ---------------------------------------------------------------- delegate picker
    @GetMapping("/api/users/approvers")
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD','SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public List<UserRef> approvers(@RequestParam(required = false) String q) {
        return directory.approvers(q).stream().map(ClaimMapper::ref).toList();
    }
}
