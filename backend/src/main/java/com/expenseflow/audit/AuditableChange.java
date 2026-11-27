package com.expenseflow.audit;

/**
 * A domain event that the audit trail records by itself. Publish it with {@code ApplicationEventPublisher}
 * <b>inside</b> the transaction that makes the change; {@link AuditService#on(AuditableChange)} writes one
 * {@code audit_events} row in that same transaction (invariant 6), so other modules never call
 * {@link AuditService} directly.
 * <p>
 * Example (L2, T-015):
 * <pre>
 * public record PolicyRuleChangedEvent(String code, Long actorId, Object before, Object after)
 *         implements AuditableChange {
 *     public String action()     { return "POLICY_RULE_UPDATED"; }
 *     public String entityType() { return AuditService.POLICY_RULE; }
 *     public Object entityId()   { return code; }
 * }
 * </pre>
 * {@code before}/{@code after} are serialised to JSON: small maps or DTOs, never JPA entities.
 */
public interface AuditableChange {

    String action();

    String entityType();

    Object entityId();

    /** Who made the change; null = take the signed-in user (or system when nobody is signed in). */
    Long actorId();

    Object before();

    Object after();

    /** Set when the actor acted for someone else (delegation). */
    default Long onBehalfOf() {
        return null;
    }
}
