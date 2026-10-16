package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;

/** One of our items inside a batch. The partner side and the match result are filled by matching (step C3). */
@Entity
@Table(name = "settlement_batch_lines")
public class SettlementBatchLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private Long batchId;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "our_status", nullable = false, updatable = false)
    private String ourStatus;

    @Column(name = "partner_status")
    private String partnerStatus;

    @Column(name = "match_result")
    private String matchResult;

    protected SettlementBatchLine() {
    }

    public SettlementBatchLine(Long batchId, String reference, String kind, long amountMinor, String ourStatus) {
        this.batchId = batchId;
        this.reference = reference;
        this.kind = kind;
        this.amountMinor = amountMinor;
        this.ourStatus = ourStatus;
    }

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public String getReference() { return reference; }
    public String getKind() { return kind; }
    public long getAmountMinor() { return amountMinor; }
    public String getOurStatus() { return ourStatus; }
    public String getPartnerStatus() { return partnerStatus; }
    public void setPartnerStatus(String partnerStatus) { this.partnerStatus = partnerStatus; }
    public String getMatchResult() { return matchResult; }
    public void setMatchResult(String matchResult) { this.matchResult = matchResult; }
}