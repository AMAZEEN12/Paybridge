package com.academy.paybridge.remittance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One payout to a bank account. The money amounts are fixed by the quote and never change after the reserve. */
@Entity
@Table(name = "remittances")
public class Remittance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "source_account_number", nullable = false, updatable = false)
    private String sourceAccountNumber;

    @Column(name = "source_currency", nullable = false, updatable = false)
    private String sourceCurrency;

    @Column(name = "payout_currency", nullable = false, updatable = false)
    private String payoutCurrency;

    @Column(name = "source_amount_minor", nullable = false, updatable = false)
    private long sourceAmountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "payout_amount_minor", nullable = false, updatable = false)
    private long payoutAmountMinor;

    @Column(nullable = false, updatable = false)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemittanceStatus status;

    @Column(name = "conversion_done", nullable = false)
    private boolean conversionDone;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "rate_difference_minor")
    private Long rateDifferenceMinor;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_payout_ref")
    private String providerPayoutRef;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Remittance() {
    }

    public Remittance(String reference, Long customerId, UUID quoteId, String sourceAccountNumber,
                      String sourceCurrency, String payoutCurrency, long sourceAmountMinor, long feeMinor,
                      long payoutAmountMinor, String purpose, String idempotencyKey, String requestHash,
                      Instant now) {
        this.reference = reference;
        this.customerId = customerId;
        this.quoteId = quoteId;
        this.sourceAccountNumber = sourceAccountNumber;
        this.sourceCurrency = sourceCurrency;
        this.payoutCurrency = payoutCurrency;
        this.sourceAmountMinor = sourceAmountMinor;
        this.feeMinor = feeMinor;
        this.payoutAmountMinor = payoutAmountMinor;
        this.purpose = purpose;
        this.status = RemittanceStatus.PENDING;
        this.provider = "FLUTTERWAVE";
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public Long getCustomerId() { return customerId; }
    public UUID getQuoteId() { return quoteId; }
    public String getSourceAccountNumber() { return sourceAccountNumber; }
    public String getSourceCurrency() { return sourceCurrency; }
    public String getPayoutCurrency() { return payoutCurrency; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getFeeMinor() { return feeMinor; }
    public long getPayoutAmountMinor() { return payoutAmountMinor; }
    public String getPurpose() { return purpose; }
    public RemittanceStatus getStatus() { return status; }
    public void setStatus(RemittanceStatus status) { this.status = status; }
    public boolean isConversionDone() { return conversionDone; }
    public void setConversionDone(boolean conversionDone) { this.conversionDone = conversionDone; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Long getRateDifferenceMinor() { return rateDifferenceMinor; }
    public void setRateDifferenceMinor(Long rateDifferenceMinor) { this.rateDifferenceMinor = rateDifferenceMinor; }
    public String getProvider() { return provider; }
    public String getProviderPayoutRef() { return providerPayoutRef; }
    public void setProviderPayoutRef(String providerPayoutRef) { this.providerPayoutRef = providerPayoutRef; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason == null ? null
                : (failureReason.length() > 300 ? failureReason.substring(0, 300) : failureReason);
    }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}