package com.academy.paybridge.compliance.domain;

import com.academy.paybridge.compliance.api.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "fraud_flags")
public class FraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(nullable = false)
    private String reasons;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FraudFlag() {
    }

    public FraudFlag(String reference, String accountNumber, Decision decision, String reasons) {
        this.reference = reference;
        this.accountNumber = accountNumber;
        this.decision = decision;
        this.reasons = reasons.length() > 500 ? reasons.substring(0, 500) : reasons;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public String getAccountNumber() { return accountNumber; }
    public Decision getDecision() { return decision; }
    public String getReasons() { return reasons; }
    public Instant getCreatedAt() { return createdAt; }
}
