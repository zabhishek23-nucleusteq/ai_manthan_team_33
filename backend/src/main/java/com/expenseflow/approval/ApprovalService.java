package com.expenseflow.approval;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.ApprovalDecision;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ApprovalStep;
import com.expenseflow.domain.AuditActions;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.User;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyService;
import com.expenseflow.repository.ApprovalStepRepository;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.service.ClaimEvents.ClaimRoutedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Approval chain (ADR-001, FR-6..FR-8, BR-10..BR-16). Steps are created lazily: only the current level is open.
 * The step's assignee is the natural approver; an active delegate of that approver may act for them
 * (decision records actedBy = delegate, onBehalfOf = assignee). Every decision is audited in the same transaction.
 */
@Service
public class ApprovalService {

    private final ClaimRepository claims;
    private final UserRepository users;
    private final ApprovalStepRepository steps;
    private final ApproverDirectory directory;
    private final PolicyService policy;
    private final ApplicationEventPublisher events;

    public ApprovalService(ClaimRepository claims, UserRepository users, ApprovalStepRepository steps,
                           ApproverDirectory directory, PolicyService policy, ApplicationEventPublisher events) {
        this.claims = claims;
        this.users = users;
        this.steps = steps;
        this.directory = directory;
        this.policy = policy;
        this.events = events;
    }

    static ClaimStatus pendingStatus(ApprovalLevel level) {
        return switch (level) {
            case MANAGER -> ClaimStatus.PENDING_MANAGER;
            case AUDITOR -> ClaimStatus.PENDING_AUDITOR;
            case HEAD -> ClaimStatus.PENDING_HEAD;
        };
    }

    // ---------------------------------------------------------------- chain start (submit / resubmit)
    /** Submit ROUTED the claim (same transaction): open the first level of round {@code round}, starting at MANAGER (BR-16). */
    @EventListener
    @Transactional
    public void onRouted(ClaimRoutedEvent e) {
        Claim c = claims.findById(e.claimId()).orElseThrow();
        Set<ApprovalLevel> required = new HashSet<>(e.requiredLevels());
        required.add(ApprovalLevel.MANAGER);
        if (!openFrom(c, e.round(), ApprovalLevel.MANAGER, required, false)) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "No eligible approver for this claim");
        }
        claims.saveAndFlush(c);
    }

    /**
     * Opens the first level from {@code from} onwards that is required (or forced by an escalation) and has an
     * eligible approver. A level whose approver is missing, is the claimant, or has delegated to the claimant is
     * skipped and the next level becomes mandatory (BR-15).
     * @return false if no further level was opened
     */
    private boolean openFrom(Claim c, int round, ApprovalLevel from, Set<ApprovalLevel> required, boolean forced) {
        User claimant = c.getEmployee();
        ApprovalLevel[] all = ApprovalLevel.values();
        for (int i = from.ordinal(); i < all.length; i++) {
            ApprovalLevel level = all[i];
            if (!required.contains(level) && !forced) continue;
            Optional<User> approver = directory.resolve(level, claimant);
            boolean delegatedToClaimant = approver.flatMap(a -> directory.activeDelegate(a.getId()))
                    .map(d -> d.getId().equals(claimant.getId())).orElse(false);
            if (approver.isEmpty() || delegatedToClaimant) {
                events.publishEvent(new ApprovalEvents.Escalated(c.getId(), Map.of("level", level.name()),
                        Map.of("reason", approver.isEmpty() ? "NO_APPROVER" : "DELEGATED_TO_CLAIMANT", "round", round)));
                forced = true;
                continue;
            }
            steps.save(new ApprovalStep(c, round, level, approver.get()));
            c.setStatus(pendingStatus(level));
            return true;
        }
        if (forced) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "No eligible approver left after escalation");
        }
        return false;
    }

    // ---------------------------------------------------------------- decisions
    @Transactional
    public Claim decide(long actorId, long claimId, ApprovalDecision decision, String comment) {
        Claim c = claims.findByIdForUpdate(claimId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Claim not found"));
        if (c.isOwnedBy(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SELF_APPROVAL_FORBIDDEN, "You cannot decide on your own claim", null);
        }
        ApprovalStep step = steps.findFirstByClaimIdAndDecisionIsNull(claimId).orElse(null);
        User assignee = step == null ? null : step.getAssignee();
        boolean isAssignee = assignee != null && assignee.getId().equals(actorId);
        boolean isDelegate = assignee != null && !isAssignee
                && directory.activeDelegate(assignee.getId()).map(d -> d.getId().equals(actorId)).orElse(false);
        if (step == null || c.getStatus() != pendingStatus(step.getLevel())) {
            if (steps.isApproverOf(claimId, actorId)) {
                throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION, "Claim is " + c.getStatus() + " and has no open step for you");
            }
            throw notAssigned();
        }
        if (!isAssignee && !isDelegate) throw notAssigned();

        User actor = users.findById(actorId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        Instant now = Instant.now();
        ClaimStatus before = c.getStatus();
        step.decide(decision, actor, isDelegate ? assignee : null, comment, now);
        steps.save(step);

        String action;
        switch (decision) {
            case APPROVED -> {
                action = AuditActions.CLAIM_APPROVED;
                ApprovalLevel[] all = ApprovalLevel.values();
                boolean opened = step.getLevel().ordinal() + 1 < all.length
                        && openFrom(c, step.getRound(), all[step.getLevel().ordinal() + 1], requiredLevels(c), false);
                if (!opened) {
                    c.setStatus(ClaimStatus.APPROVED);
                    c.setDecidedAt(now);
                }
            }
            case REJECTED -> {
                action = AuditActions.CLAIM_REJECTED;
                c.setStatus(ClaimStatus.REJECTED);
                c.setDecidedAt(now);
            }
            case SENT_BACK -> {
                action = AuditActions.CLAIM_SENT_BACK;
                c.setStatus(ClaimStatus.CLARIFICATION_REQUESTED);
            }
            default -> throw new IllegalStateException();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", c.getStatus().name());
        after.put("level", step.getLevel().name());
        after.put("round", step.getRound());
        if (comment != null) after.put("comment", comment);
        events.publishEvent(new ApprovalEvents.Decided(action, c.getId(), actorId, isDelegate ? assignee.getId() : null,
                Map.of("status", before.name()), after));
        return claims.saveAndFlush(c);
    }

    private static ApiException notAssigned() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.NOT_ASSIGNED_APPROVER, "You are not the assigned approver of this claim", null);
    }

    private Set<ApprovalLevel> requiredLevels(Claim c) {
        PolicyResult r = policy.readSnapshot(c.getPolicyResult());
        Set<ApprovalLevel> req = new HashSet<>();
        if (r != null && r.requiredLevels() != null) req.addAll(r.requiredLevels());
        req.add(ApprovalLevel.MANAGER);
        return req;
    }

    // ---------------------------------------------------------------- inbox
    /** An open step for the user: assigned to them, or to someone who delegated to them today (never their own claim). */
    public record InboxItem(ApprovalStep step, User onBehalfOf) {
    }

    @Transactional(readOnly = true)
    public List<InboxItem> inbox(long userId) {
        List<InboxItem> out = new ArrayList<>();
        for (ApprovalStep s : steps.findByAssigneeIdAndDecisionIsNull(userId)) out.add(new InboxItem(s, null));
        for (Long delegator : directory.delegatorsOf(userId)) {
            for (ApprovalStep s : steps.findByAssigneeIdAndDecisionIsNull(delegator)) out.add(new InboxItem(s, s.getAssignee()));
        }
        out.removeIf(i -> i.step().getClaim().isOwnedBy(userId)
                || i.step().getClaim().getStatus() != pendingStatus(i.step().getLevel()));
        out.sort(Comparator.comparing((InboxItem i) -> i.step().getCreatedAt()).thenComparing(i -> i.step().getId()));
        return out;
    }
}
