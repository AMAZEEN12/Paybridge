package com.academy.paybridge.fx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A locked price quote for a currency conversion or transfer (R-QUO-1 to R-QUO-8).
 */
@Entity
@Table(name = "quotes")
public class Quote {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteType type;

    @Column(name = "source_currency", nullable = false, length = 3)
    private String sourceCurrency;

    @Column(name = "target_currency", nullable = false, length = 3)
    private String targetCurrency;

    @Column(name = "source_amount_minor", nullable = false)
    private long sourceAmountMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_reason", nullable = false, length = 20)
    private FeeReason feeReason;

    @Column(name = "net_minor", nullable = false)
    private long netMinor;

    @Column(name = "target_amount_minor", nullable = false)
    private long targetAmountMinor;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal rate;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "usd_equivalent_minor", nullable = false)
    private long usdEquivalentMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "journal_key", length = 60)
    private String journalKey;

    protected Quote() {
    }

    public Quote(UUID id, Long customerId, QuoteType type,
                 String sourceCurrency, String targetCurrency,
                 long sourceAmountMinor, long feeMinor, FeeReason feeReason,
                 long netMinor, long targetAmountMinor,
                 BigDecimal rate, Long snapshotId, long usdEquivalentMinor,
                 QuoteStatus status, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.sourceCurrency = sourceCurrency;
        this.targetCurrency = targetCurrency;
        this.sourceAmountMinor = sourceAmountMinor;
        this.feeMinor = feeMinor;
        this.feeReason = feeReason;
        this.netMinor = netMinor;
        this.targetAmountMinor = targetAmountMinor;
        this.rate = rate;
        this.snapshotId = snapshotId;
        this.usdEquivalentMinor = usdEquivalentMinor;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public QuoteType getType() {
        return type;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public String getTargetCurrency() {
        return targetCurrency;
    }

    public long getSourceAmountMinor() {
        return sourceAmountMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public FeeReason getFeeReason() {
        return feeReason;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public long getTargetAmountMinor() {
        return targetAmountMinor;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public long getUsdEquivalentMinor() {
        return usdEquivalentMinor;
    }

    public QuoteStatus getStatus() {
        return status;
    }

    public void setStatus(QuoteStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getJournalKey() {
        return journalKey;
    }

    public void setJournalKey(String journalKey) {
        this.journalKey = journalKey;
    }
}
