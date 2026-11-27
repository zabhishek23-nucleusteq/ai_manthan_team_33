package com.expenseflow.approval;

import com.expenseflow.audit.AuditService;
import com.expenseflow.audit.AuditableChange;

import java.util.Map;

/** Audited events of the approval chain and delegations (invariant 6: written in the same transaction). */
public final class ApprovalEvents {

    public static final String DELEGATION_REVOKED = "DELEGATION_REVOKED";
    public static final String APPROVAL_STEP_ESCALATED = "APPROVAL_STEP_ESCALATED";

    private ApprovalEvents() {
    }

    /** An approver (or their delegate) decided a step: CLAIM_APPROVED / CLAIM_REJECTED / CLAIM_SENT_BACK. */
    public record Decided(String action, Long claimId, Long actorId, Long onBehalfOf, Map<String, Object> before,
                          Map<String, Object> after) implements AuditableChange {
        @Override public String entityType() { return AuditService.CLAIM; }
        @Override public Object entityId() { return claimId; }
    }

    /** A level was skipped because its approver is missing or is the claimant (BR-15). */
    public record Escalated(Long claimId, Map<String, Object> before, Map<String, Object> after) implements AuditableChange {
        @Override public String action() { return APPROVAL_STEP_ESCALATED; }
        @Override public String entityType() { return AuditService.CLAIM; }
        @Override public Object entityId() { return claimId; }
        @Override public Long actorId() { return null; }
    }

    /** DELEGATION_CREATED / DELEGATION_REVOKED. */
    public record DelegationChanged(String action, Long delegationId, Long actorId, Map<String, Object> before,
                                    Map<String, Object> after) implements AuditableChange {
        @Override public String entityType() { return AuditService.DELEGATION; }
        @Override public Object entityId() { return delegationId; }
    }
}
