package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** A line of the partner's statement, stored as fetched and never edited. */
@Entity
@Table(name = "partner_statement_lines")
public class PartnerStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String partner;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String status;

    @Column(name = "partner_time", updatable = false)
    private Instant partnerTime;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    @Column(name = "batch_id", updatable = false)
    private Long batchId;

    protected PartnerStatementLine() {
    }

    public PartnerStatementLine(String partner, String currency, String reference, long amountMinor, String status,
                                Instant partnerTime, Instant fetchedAt, Long batchId) {
        this.partner = partner;
        this.currency = currency;
        this.reference = reference;
        this.amountMinor = amountMinor;
        this.status = status;
        this.partnerTime = partnerTime;
        this.fetchedAt = fetchedAt;
        this.batchId = batchId;
    }

    public String getReference() { return reference; }
    public long getAmountMinor() { return amountMinor; }
    public String getStatus() { return status; }
    public Long getBatchId() { return batchId; }
}