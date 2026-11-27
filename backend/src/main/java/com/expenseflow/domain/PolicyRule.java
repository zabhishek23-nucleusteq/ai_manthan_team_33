package com.expenseflow.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Policy rule or setting stored as configuration (ADR-002, seeded by V4__policy_rules.sql, edited by FINANCE_HEAD).
 * Rule codes (BR-1..BR-8): MEAL_DAILY_LIMIT, HOTEL_NIGHTLY_LIMIT, CAB_TRIP_LIMIT, RECEIPT_REQUIRED, DUPLICATE_LINE,
 * DUPLICATE_RECEIPT, WEEKEND_SPEND. Settings (no action): AUTO_APPROVE_MAX, CHAIN_AUDITOR_ABOVE, CHAIN_HEAD_ABOVE,
 * SAMPLE_AUDIT_RATE. Extra values live in {@code params} JSON (e.g. tier1Cities, otherCitiesLimit).
 */
@Entity
@Table(name = "policy_rules")
public class PolicyRule {

    public static final String MEAL_DAILY_LIMIT = "MEAL_DAILY_LIMIT";
    public static final String HOTEL_NIGHTLY_LIMIT = "HOTEL_NIGHTLY_LIMIT";
    public static final String CAB_TRIP_LIMIT = "CAB_TRIP_LIMIT";
    public static final String RECEIPT_REQUIRED = "RECEIPT_REQUIRED";
    public static final String DUPLICATE_LINE = "DUPLICATE_LINE";
    public static final String DUPLICATE_RECEIPT = "DUPLICATE_RECEIPT";
    public static final String WEEKEND_SPEND = "WEEKEND_SPEND";
    public static final String AUTO_APPROVE_MAX = "AUTO_APPROVE_MAX";
    public static final String CHAIN_AUDITOR_ABOVE = "CHAIN_AUDITOR_ABOVE";
    public static final String CHAIN_HEAD_ABOVE = "CHAIN_HEAD_ABOVE";
    public static final String SAMPLE_AUDIT_RATE = "SAMPLE_AUDIT_RATE";

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ExpenseCategory category;

    @Column(name = "limit_amount", precision = 14, scale = 2)
    private BigDecimal limitAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RuleUnit unit;

    @JdbcTypeCode(SqlTypes.JSON)
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PolicyAction action;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PolicyRule() {
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Settings rows (thresholds, rates) have no action. */
    public boolean isSetting() {
        return action == null;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
    public ExpenseCategory getCategory() { return category; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public void setLimitAmount(BigDecimal limitAmount) { this.limitAmount = limitAmount; }
    public RuleUnit getUnit() { return unit; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public PolicyAction getAction() { return action; }
    public void setAction(PolicyAction action) { this.action = action; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public User getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(User updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
