package com.expenseflow.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** An approver hands their approvals to a delegate for a date range, max 30 days (BR-14, DB checks too). */
@Entity
@Table(name = "delegations")
public class Delegation {

    public static final int MAX_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegator_id", nullable = false)
    private User delegator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegate_id", nullable = false)
    private User delegate;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(length = 300)
    private String reason;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Delegation() {
    }

    public Delegation(User delegator, User delegate, LocalDate fromDate, LocalDate toDate, String reason) {
        this.delegator = delegator;
        this.delegate = delegate;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean coversDate(LocalDate date) {
        return active && !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    public Long getId() { return id; }
    public User getDelegator() { return delegator; }
    public User getDelegate() { return delegate; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public String getReason() { return reason; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
