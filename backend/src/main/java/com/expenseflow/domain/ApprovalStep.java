package com.expenseflow.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One level of the approval chain (ADR-001). {@code decision == null} means pending. A send-back restarts
 * at level 1 in the next {@code round} (BR-16); (claim, round, level) is unique. When a delegate acts,
 * {@code actedBy} is the delegate and {@code onBehalfOf} the original assignee (BR-14).
 */
@Entity
@Table(name = "approval_steps")
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(nullable = false)
    private int round = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApprovalLevel level;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_id", nullable = false)
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acted_by_id")
    private User actedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "on_behalf_of_id")
    private User onBehalfOf;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ApprovalDecision decision;

    @Column(length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ApprovalStep() {
    }

    public ApprovalStep(Claim claim, int round, ApprovalLevel level, User assignee) {
        this.claim = claim;
        this.round = round;
        this.level = level;
        this.assignee = assignee;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isPending() {
        return decision == null;
    }

    /**
     * Records the decision. The DB requires decided_at and acted_by for every decision.
     * @param onBehalfOf original assignee when a delegate acts, else null
     */
    public void decide(ApprovalDecision decision, User actor, User onBehalfOf, String comment, Instant at) {
        this.decision = decision;
        this.actedBy = actor;
        this.onBehalfOf = onBehalfOf;
        this.comment = comment;
        this.decidedAt = at;
    }

    public Long getId() { return id; }
    public Claim getClaim() { return claim; }
    public int getRound() { return round; }
    public ApprovalLevel getLevel() { return level; }
    public User getAssignee() { return assignee; }
    public void setAssignee(User assignee) { this.assignee = assignee; }
    public User getActedBy() { return actedBy; }
    public User getOnBehalfOf() { return onBehalfOf; }
    public ApprovalDecision getDecision() { return decision; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
