package com.expenseflow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** One expense line. HOTEL lines need {@code nights} (DB check) for the nightly limit (BR-2/BR-3). */
@Entity
@Table(name = "claim_lines")
public class ClaimLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpenseCategory category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 150)
    private String vendor;

    @Column(length = 500)
    private String description;

    @Column(length = 80)
    private String city;

    private Integer nights;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private Receipt receipt;

    /** Policy violations on this line as a JSON array of {rule, action, message}. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String violations;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClaimLine() {
    }

    public ClaimLine(ExpenseCategory category, LocalDate expenseDate, BigDecimal amount, String vendor) {
        this.category = category;
        this.expenseDate = expenseDate;
        this.amount = amount;
        this.vendor = vendor;
    }

    void attachTo(Claim claim, int lineNo) {
        this.claim = claim;
        this.lineNo = lineNo;
    }

    void renumber(int lineNo) {
        this.lineNo = lineNo;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Claim getClaim() { return claim; }
    public int getLineNo() { return lineNo; }
    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Integer getNights() { return nights; }
    public void setNights(Integer nights) { this.nights = nights; }
    public Receipt getReceipt() { return receipt; }
    public void setReceipt(Receipt receipt) { this.receipt = receipt; }
    public String getViolations() { return violations; }
    public void setViolations(String violations) { this.violations = violations; }
    public Instant getCreatedAt() { return createdAt; }
}
