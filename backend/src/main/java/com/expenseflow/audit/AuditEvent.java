package com.expenseflow.audit;

import java.time.Instant;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One append-only audit row (FR-13, NFR-AUDIT). {@link Immutable}: Hibernate never issues UPDATE for it, and
 * the database trigger rejects UPDATE/DELETE/TRUNCATE anyway (V5). Create through {@link AuditService} only.
 */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant at;

    /** Who did it; null = system (seed, scheduled job). */
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    /** Original assignee when a delegate acted (BR-14). */
    @Column(name = "on_behalf_of_id", updatable = false)
    private Long onBehalfOfId;

    @Column(nullable = false, length = 60, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 64, updatable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String after;

    protected AuditEvent() {
    }

    AuditEvent(Instant at, Long actorId, Long onBehalfOfId, String action, String entityType, String entityId,
               String before, String after) {
        this.at = at;
        this.actorId = actorId;
        this.onBehalfOfId = onBehalfOfId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.before = before;
        this.after = after;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public Long getActorId() { return actorId; }
    public Long getOnBehalfOfId() { return onBehalfOfId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    /** JSON snapshot before the change, or null. */
    public String getBefore() { return before; }
    /** JSON snapshot after the change, or null. */
    public String getAfter() { return after; }
}
