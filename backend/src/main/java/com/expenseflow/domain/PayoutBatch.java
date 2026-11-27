package com.expenseflow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Batch of approved claims exported as a mock bank file (BR-17). A claim is in at most one batch (DB unique). */
@Entity
@Table(name = "payout_batches")
public class PayoutBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PayoutBatchStatus status = PayoutBatchStatus.CREATED;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "bank_reference", length = 80)
    private String bankReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** Last bank submit problem (SUBMIT_FAILED reason or rows the bank rejected); null when fine. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "bank_file_id", length = 80)
    private String bankFileId;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PayoutItem> items = new ArrayList<>();

    protected PayoutBatch() {
    }

    public PayoutBatch(User createdBy, LocalDate valueDate) {
        this.createdBy = createdBy;
        this.valueDate = valueDate;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public PayoutItem addClaim(Claim claim) {
        PayoutItem item = new PayoutItem(this, claim, claim.getTotalAmount());
        items.add(item);
        itemCount = items.size();
        total = items.stream().map(PayoutItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return item;
    }

    public Long getId() { return id; }
    public PayoutBatchStatus getStatus() { return status; }
    public void setStatus(PayoutBatchStatus status) { this.status = status; }
    public User getCreatedBy() { return createdBy; }
    public int getItemCount() { return itemCount; }
    public BigDecimal getTotal() { return total; }
    public LocalDate getValueDate() { return valueDate; }
    public String getBankReference() { return bankReference; }
    public void setBankReference(String bankReference) { this.bankReference = bankReference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public long getVersion() { return version; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError == null || lastError.length() <= 1000 ? lastError : lastError.substring(0, 1000); }
    public String getBankFileId() { return bankFileId; }
    public void setBankFileId(String bankFileId) { this.bankFileId = bankFileId; }
    /** Batch code shown to users and in the bank file (BR-17), e.g. PB-0007. */
    public String code() { return String.format("PB-%04d", id); }

    /** Drops a claim the bank rejected so it can go into a later batch (BR-18: never paid twice). */
    public void removeItemFor(Long claimId) {
        items.removeIf(i -> i.getClaim().getId().equals(claimId));
        itemCount = items.size();
        total = items.stream().map(PayoutItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public List<PayoutItem> getItems() { return Collections.unmodifiableList(items); }
}
