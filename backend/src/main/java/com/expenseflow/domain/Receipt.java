package com.expenseflow.domain;

import java.time.Instant;

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

/**
 * Uploaded receipt file stored in the database (bytea). PDF/JPG/PNG, max 5 MB (BR-20, also a DB check).
 * {@code sha256} drives duplicate-receipt detection (BR-7). List queries should use projections so the
 * file content is not loaded.
 */
@Entity
@Table(name = "receipts")
public class Receipt {

    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Receipt() {
    }

    public Receipt(User owner, String fileName, String contentType, byte[] content, String sha256) {
        this.owner = owner;
        this.fileName = fileName;
        this.contentType = contentType;
        this.content = content;
        this.size = content.length;
        this.sha256 = sha256;
    }

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) uploadedAt = Instant.now();
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public byte[] getContent() { return content; }
    public Instant getUploadedAt() { return uploadedAt; }
}
