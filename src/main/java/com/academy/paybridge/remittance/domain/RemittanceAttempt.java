package com.academy.paybridge.remittance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One request sent to the partner. Attempt 0 is the original; a retry (retries on only) gets a NEW reference. */
@Entity
@Table(name = "remittance_attempts")
public class RemittanceAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "remittance_id", nullable = false, updatable = false)
    private Long remittanceId;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(name = "customer_reference", nullable = false, updatable = false)
    private String customerReference;

    @Column(name = "provider_payout_ref")
    private String providerPayoutRef;

    @Column(nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RemittanceAttempt() {
    }

    public RemittanceAttempt(Long remittanceId, int attempt, String customerReference, String providerPayoutRef,
                             String result, Instant now) {
        this.remittanceId = remittanceId;
        this.attempt = attempt;
        this.customerReference = customerReference;
        this.providerPayoutRef = providerPayoutRef;
        this.result = result;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getRemittanceId() { return remittanceId; }
    public int getAttempt() { return attempt; }
    public String getCustomerReference() { return customerReference; }
    public String getProviderPayoutRef() { return providerPayoutRef; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Instant getCreatedAt() { return createdAt; }
}