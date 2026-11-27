package com.expenseflow.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ClaimDtos.ClaimInput;
import com.expenseflow.api.ClaimDtos.ClaimLineInput;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.AuditActions;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimComment;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.domain.Receipt;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.domain.User;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyService;
import com.expenseflow.repository.ApprovalStepRepository;
import com.expenseflow.repository.ClaimCommentRepository;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.ReceiptRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.service.ClaimEvents.ClaimChanged;
import com.expenseflow.service.ClaimEvents.ClaimRoutedEvent;

/**
 * Employee side of the claim workflow (FR-2, FR-3, FR-4, FR-14). Row rules (rbac.md section 3): only the owner
 * edits/submits; owner, assigned approvers (now or before), FINANCE_AUDITOR and FINANCE_HEAD may view; everyone else
 * gets 404 (no enumeration). State changes follow docs/architecture/README.md section 3 and are all audited.
 */
@Service
public class ClaimService {

    private static final Set<ClaimStatus> EDITABLE = Set.of(ClaimStatus.DRAFT, ClaimStatus.CLARIFICATION_REQUESTED);

    private final ClaimRepository claims;
    private final UserRepository users;
    private final ReceiptRepository receipts;
    private final ApprovalStepRepository steps;
    private final ClaimCommentRepository comments;
    private final PolicyService policy;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public ClaimService(ClaimRepository claims, UserRepository users, ReceiptRepository receipts, ApprovalStepRepository steps,
                        ClaimCommentRepository comments, PolicyService policy, ApplicationEventPublisher events) {
        this.claims = claims;
        this.users = users;
        this.receipts = receipts;
        this.steps = steps;
        this.comments = comments;
        this.policy = policy;
        this.events = events;
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCodes.NOT_FOUND, "Claim not found");
    }

    private Claim owned(long userId, long claimId) {
        return claims.findByIdAndEmployeeId(claimId, userId).orElseThrow(ClaimService::notFound);
    }

    // ---------------------------------------------------------------- read
    @Transactional(readOnly = true)
    public Page<Claim> myClaims(long userId, ClaimStatus status, int page, int size) {
        PageRequest p = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        return status == null ? claims.findByEmployeeId(userId, p) : claims.findByEmployeeIdAndStatus(userId, status, p);
    }

    /** Owner, assigned approvers (now or before), FINANCE_AUDITOR, FINANCE_HEAD; others 404. */
    @Transactional(readOnly = true)
    public Claim visible(long userId, Set<String> roles, long claimId) {
        Claim c = claims.findById(claimId).orElseThrow(ClaimService::notFound);
        if (c.isOwnedBy(userId) || roles.contains(Role.FINANCE_AUDITOR) || roles.contains(Role.FINANCE_HEAD)
                || steps.isApproverOf(claimId, userId)) {
            return c;
        }
        throw notFound();
    }

    // ---------------------------------------------------------------- write
    @Transactional
    public Claim create(long userId, ClaimInput in) {
        User owner = users.findById(userId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        String code = "EXP-" + Year.now(ZoneOffset.UTC).getValue() + "-" + claims.nextCodeNumber();
        Claim c = new Claim(code, owner, in.title().trim());
        applyLines(c, in.lines(), userId);
        claims.saveAndFlush(c);
        events.publishEvent(new ClaimChanged(AuditActions.CLAIM_CREATED, c.getId(), userId, null,
                Map.of("status", "DRAFT", "code", code, "total", c.getTotalAmount())));
        return c;
    }

    @Transactional
    public Claim update(long userId, long claimId, ClaimInput in) {
        Claim c = owned(userId, claimId);
        requireEditable(c);
        c.setTitle(in.title().trim());
        new ArrayList<>(c.getLines()).forEach(c::removeLine);
        claims.saveAndFlush(c);           // orphan removal of old lines before line numbers are reused
        applyLines(c, in.lines(), userId);
        return claims.saveAndFlush(c);
    }

    @Transactional
    public void delete(long userId, long claimId) {
        Claim c = owned(userId, claimId);
        if (c.getStatus() != ClaimStatus.DRAFT) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "Only draft claims can be deleted (claim is " + c.getStatus() + ")");
        }
        claims.delete(c);
    }

    /**
     * Submit or resubmit (FR-4): runs the policy engine, stores the result, moves the claim by outcome (BR-9).
     * AUTO_APPROVED -> APPROVED (+ sample audit, BR-13); AUTO_REJECTED -> AUTO_REJECTED; ROUTED -> PENDING_MANAGER and
     * ClaimRoutedEvent for the approval service. Concurrent submits: optimistic @Version -> 409 CONCURRENT_MODIFICATION.
     */
    @Transactional
    public Claim submit(long userId, long claimId) {
        Claim c = owned(userId, claimId);
        if (!EDITABLE.contains(c.getStatus())) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "Claim is " + c.getStatus() + " and cannot be submitted");
        }
        if (c.getLines().isEmpty()) {
            throw ApiException.refused(ErrorCodes.CLAIM_EMPTY, "Add at least one line before submitting", null);
        }
        ClaimStatus before = c.getStatus();
        Instant now = Instant.now();
        if (c.getSubmittedAt() == null) c.setSubmittedAt(now);
        events.publishEvent(new ClaimChanged(AuditActions.CLAIM_SUBMITTED, c.getId(), userId, Map.of("status", before.name()),
                Map.of("total", c.getTotalAmount())));

        PolicyResult r = policy.evaluateAndApply(c);
        switch (r.outcome()) {
            case AUTO_APPROVED -> {
                c.setStatus(ClaimStatus.APPROVED);
                c.setDecidedAt(now);
                boolean sampled = random.nextDouble() < sampleRate();
                c.setSampleAudit(sampled ? SampleAudit.PENDING : SampleAudit.NONE);
                events.publishEvent(new ClaimChanged(AuditActions.CLAIM_AUTO_APPROVED, c.getId(), null,
                        Map.of("status", before.name()), Map.of("status", "APPROVED", "policyOutcome", "AUTO_APPROVED")));
                if (sampled) {
                    events.publishEvent(new ClaimChanged(AuditActions.CLAIM_SAMPLE_AUDIT_FLAGGED, c.getId(), null,
                            Map.of("sampleAudit", "NONE"), Map.of("sampleAudit", "PENDING")));
                }
            }
            case AUTO_REJECTED -> {
                c.setStatus(ClaimStatus.AUTO_REJECTED);
                c.setDecidedAt(now);
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("status", "AUTO_REJECTED");
                after.put("reasons", r.reasons());
                events.publishEvent(new ClaimChanged(AuditActions.CLAIM_AUTO_REJECTED, c.getId(), null, Map.of("status", before.name()), after));
            }
            case ROUTED -> {
                c.setStatus(ClaimStatus.PENDING_MANAGER);
                c.setDecidedAt(null);
                claims.saveAndFlush(c);
                events.publishEvent(new ClaimRoutedEvent(c.getId(), userId, r.requiredLevels(), steps.maxRound(c.getId()) + 1, null));
            }
        }
        return claims.saveAndFlush(c);
    }

    // ---------------------------------------------------------------- comments
    @Transactional(readOnly = true)
    public List<ClaimComment> comments(long userId, Set<String> roles, long claimId) {
        visible(userId, roles, claimId);
        return comments.findByClaimIdOrderByCreatedAtAsc(claimId);
    }

    @Transactional
    public ClaimComment addComment(long userId, Set<String> roles, long claimId, String text) {
        Claim c = visible(userId, roles, claimId);
        User author = users.findById(userId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        return comments.save(new ClaimComment(c, author, text.trim()));
    }

    // ---------------------------------------------------------------- helpers
    private double sampleRate() {
        RuleConfig rate = policy.currentRules().get(PolicyRule.SAMPLE_AUDIT_RATE);
        return rate == null || rate.limitAmount() == null || !rate.enabled() ? 0.10 : rate.limitAmount().doubleValue();
    }

    private static void requireEditable(Claim c) {
        if (!c.isEditable()) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION,
                    "Claim is " + c.getStatus() + "; it can be edited only in DRAFT or CLARIFICATION_REQUESTED");
        }
    }

    private void applyLines(Claim c, List<ClaimLineInput> lines, long userId) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ClaimLineInput in = lines.get(i);
            if (in.category() == ExpenseCategory.HOTEL) {
                if (in.city() == null || in.city().isBlank()) fieldErrors.put("lines[" + i + "].city", "required for HOTEL");
                if (in.nights() == null) fieldErrors.put("lines[" + i + "].nights", "required for HOTEL");
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request has invalid fields", Map.of("fields", fieldErrors));
        }
        for (int i = 0; i < lines.size(); i++) {
            ClaimLineInput in = lines.get(i);
            ClaimLine l = new ClaimLine(in.category(), in.expenseDate(), in.amount().setScale(2, java.math.RoundingMode.HALF_UP), in.vendor().trim());
            l.setDescription(in.description());
            l.setCity(in.city() == null ? null : in.city().trim());
            l.setNights(in.nights());
            if (in.receiptId() != null) {
                Receipt r = receipts.findById(in.receiptId())
                        .filter(x -> x.getOwner().getId().equals(userId))
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Unknown receipt",
                                Map.of("fields", Map.of("lines[" + lines.indexOf(in) + "].receiptId", "not one of your receipts"))));
                l.setReceipt(r);
            }
            c.addLine(l);
        }
    }

    /** Used by the mapper: total of the stored lines (kept in sync by the entity). */
    public static BigDecimal total(Claim c) {
        return c.getTotalAmount();
    }
}
