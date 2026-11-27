package com.expenseflow.policy;

import java.util.Map;

/**
 * Published (synchronously, inside the update transaction) when FINANCE_HEAD changes a policy rule.
 * The audit trail (L3, AuditService) records it as action POLICY_RULE_UPDATED, entityType POLICY_RULE,
 * entityId = code, with before/after values.
 */
public record PolicyRuleChangedEvent(String code, Long actorId, Map<String, Object> before, Map<String, Object> after) {
}
