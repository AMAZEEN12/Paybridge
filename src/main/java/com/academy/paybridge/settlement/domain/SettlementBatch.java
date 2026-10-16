package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** One partner, one currency, one UTC day. Written once, already CLOSED (R-SET-3, R-SET-5). */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String partner;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "period_date", nullable = false, updatable = false)
    private LocalDate periodDate;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "our_total_minor", nullable = false, updatable = false)
    private long ourTotalMinor;

    @Column(name = "partner_total_minor", nullable = false, updatable = false)
    private long partnerTotalMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected SettlementBatch() {
    }

    public static SettlementBatch closed(String partner, String currency, LocalDate date, Instant start, Instant end,
                                         int count, long ourTotal, long partnerTotal, Instant now) {
        SettlementBatch b = new SettlementBatch();
        b.partner = partner;
        b.currency = currency;
        b.periodDate = date;
        b.periodStart = start;
        b.periodEnd = end;
        b.status = BatchStatus.CLOSED;
        b.itemCount = count;
        b.ourTotalMinor = ourTotal;
        b.partnerTotalMinor = partnerTotal;
        b.createdAt = now;
        b.closedAt = now;
        return b;
    }

    public Long getId() { return id; }
    public String getPartner() { return partner; }
    public String getCurrency() { return currency; }
    public LocalDate getPeriodDate() { return periodDate; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public BatchStatus getStatus() { return status; }
    public int getItemCount() { return itemCount; }
    public long getOurTotalMinor() { return ourTotalMinor; }
    public long getPartnerTotalMinor() { return partnerTotalMinor; }
    public Instant getClosedAt() { return closedAt; }
}