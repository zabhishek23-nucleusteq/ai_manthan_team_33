package com.expenseflow.policy;

import java.util.Map;

import com.expenseflow.audit.AuditService;
import com.expenseflow.audit.AuditableChange;
import com.expenseflow.domain.AuditActions;

/**
 * Published (synchronously, inside the update transaction) when FINANCE_HEAD changes a policy rule.
 * As an {@link AuditableChange} the audit trail records it by itself: action POLICY_RULE_UPDATED,
 * entityType POLICY_RULE, entityId = code, with before/after values, in the same transaction.
 */
public record PolicyRuleChangedEvent(String code, Long actorId, Map<String, Object> before, Map<String, Object> after)
        implements AuditableChange {

    @Override
    public String action() {
        return AuditActions.POLICY_RULE_UPDATED;
    }

    @Override
    public String entityType() {
        return AuditService.POLICY_RULE;
    }

    @Override
    public Object entityId() {
        return code;
    }
}
