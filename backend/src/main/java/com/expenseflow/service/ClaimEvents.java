package com.expenseflow.service;

import java.util.List;
import java.util.Map;

import com.expenseflow.audit.AuditService;
import com.expenseflow.audit.AuditableChange;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.AuditActions;

/** Events the claim workflow publishes inside its transaction. All are audited by AuditService automatically. */
public final class ClaimEvents {

    private ClaimEvents() {
    }

    /** Generic audited claim change (CLAIM_CREATED, CLAIM_SUBMITTED, CLAIM_AUTO_APPROVED, CLAIM_AUTO_REJECTED, ...). */
    public record ClaimChanged(String action, Long claimId, Long actorId, Map<String, Object> before, Map<String, Object> after)
            implements AuditableChange {

        @Override public String entityType() { return AuditService.CLAIM; }
        @Override public Object entityId() { return claimId; }
    }

    /**
     * A submitted claim was ROUTED by the policy engine and is now PENDING_MANAGER. The approval service (L3, T-019)
     * listens with {@code @EventListener} (same transaction) and creates the steps for {@code requiredLevels},
     * starting at MANAGER in approval round {@code round} (BR-10..BR-12, BR-15, BR-16). Audited as CLAIM_ROUTED.
     */
    public record ClaimRoutedEvent(Long claimId, Long employeeId, List<ApprovalLevel> requiredLevels, int round, Long actorId)
            implements AuditableChange {

        @Override public String action() { return AuditActions.CLAIM_ROUTED; }
        @Override public String entityType() { return AuditService.CLAIM; }
        @Override public Object entityId() { return claimId; }
        @Override public Object before() { return Map.of("status", "SUBMITTED"); }
        @Override public Object after() { return Map.of("status", "PENDING_MANAGER", "requiredLevels", requiredLevels, "round", round); }
    }
}
