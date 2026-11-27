package com.expenseflow.audit;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the audit trail (FR-13, NFR-AUDIT, invariant 6). Call it from the service method that makes the change:
 * <pre>
 * auditService.record("CLAIM_SUBMITTED", AuditService.CLAIM, claim.getId(), Map.of("status", "DRAFT"),
 *         Map.of("status", claim.getStatus(), "outcome", outcome), null);
 * </pre>
 * {@link Propagation#MANDATORY}: it joins the caller's transaction, so the change and its audit row commit or
 * roll back together; calling it without a transaction is a bug and fails fast.
 * <p>
 * {@code before}/{@code after} are serialised to JSON - pass small maps or DTOs, never JPA entities (lazy
 * proxies, passwords). The actor is the authenticated user (JWT {@code sub}); null for system work.
 */
@Service
public class AuditService {

    public static final String CLAIM = "CLAIM";
    public static final String POLICY_RULE = "POLICY_RULE";
    public static final String DELEGATION = "DELEGATION";
    public static final String PAYOUT_BATCH = "PAYOUT_BATCH";
    public static final String USER = "USER";

    private final AuditEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    public AuditService(AuditEventRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(String action, String entityType, Object entityId, Object before, Object after,
                             Long onBehalfOf) {
        if (action == null || action.isBlank() || entityType == null || entityId == null) {
            throw new IllegalArgumentException("action, entityType and entityId are required");
        }
        AuditEvent event = new AuditEvent(Instant.now(clock), currentUserId(), onBehalfOf, action, entityType,
                String.valueOf(entityId), toJson(before), toJson(after));
        return events.save(event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(String action, String entityType, Object entityId, Object before, Object after) {
        return record(action, entityType, entityId, before, after, null);
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    /** User id from the JWT subject, or null when no user is signed in (seed, jobs). */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
