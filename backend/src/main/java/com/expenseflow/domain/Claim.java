package com.expenseflow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Expense claim (aggregate root with its lines). Status changes go through the claim workflow /
 * state machine only (docs/architecture/README.md section 3) - never set status from a controller.
 * {@code version} gives optimistic locking so two approvers acting at once cannot both win.
 */
@Entity
@Table(name = "claims")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable code, e.g. EXP-2026-00412; also the bank file narration (BR-17). */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private User employee;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_outcome", length = 20)
    private PolicyOutcome policyOutcome;

    /** PolicyResult snapshot as JSON (ADR-002): outcome, line violations, reasons, rules used. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_result")
    private String policyResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_audit", nullable = false, length = 10)
    private SampleAudit sampleAudit = SampleAudit.NONE;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<ClaimLine> lines = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String code, User employee, String title) {
        this.code = code;
        this.employee = employee;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Adds a line with the next line number and keeps the total in sync. */
    public ClaimLine addLine(ClaimLine line) {
        line.attachTo(this, lines.size() + 1);
        lines.add(line);
        recalculateTotal();
        return line;
    }

    public void removeLine(ClaimLine line) {
        lines.remove(line);
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).renumber(i + 1);
        }
        recalculateTotal();
    }

    public void recalculateTotal() {
        totalAmount = lines.stream().map(ClaimLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isOwnedBy(Long userId) {
        return employee != null && employee.getId() != null && employee.getId().equals(userId);
    }

    /** Lines can be edited only in DRAFT and CLARIFICATION_REQUESTED (rbac.md row rules). */
    public boolean isEditable() {
        return status == ClaimStatus.DRAFT || status == ClaimStatus.CLARIFICATION_REQUESTED;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public User getEmployee() { return employee; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public ClaimStatus getStatus() { return status; }
    /** Use only from the claim workflow / state machine. */
    public void setStatus(ClaimStatus status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public PolicyOutcome getPolicyOutcome() { return policyOutcome; }
    public void setPolicyOutcome(PolicyOutcome policyOutcome) { this.policyOutcome = policyOutcome; }
    public String getPolicyResult() { return policyResult; }
    public void setPolicyResult(String policyResult) { this.policyResult = policyResult; }
    public SampleAudit getSampleAudit() { return sampleAudit; }
    public void setSampleAudit(SampleAudit sampleAudit) { this.sampleAudit = sampleAudit; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getCreatedAt() { return createdAt; }
    /** For seed data with historical dates only. */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<ClaimLine> getLines() { return Collections.unmodifiableList(lines); }
}
