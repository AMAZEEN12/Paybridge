package com.academy.paybridge.ledger.domain;

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
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_key", nullable = false, updatable = false)
    private String journalKey;

    @Column(updatable = false)
    private String reference;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "entry_type", nullable = false, updatable = false)
    private String entryType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            String journalKey,
            String reference,
            String accountNumber,
            Direction direction,
            long amountMinor,
            String currency,
            String entryType,
            Instant now
    ) {
        this.journalKey = journalKey;
        this.reference = reference;
        this.accountNumber = accountNumber;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.entryType = entryType;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getJournalKey() {
        return journalKey;
    }

    public String getReference() {
        return reference;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    /**
     * Backward-compatible alias for older NGN code.
     * The stored value is expressed in the currency's minor units.
     */
    public long getAmountKobo() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getEntryType() {
        return entryType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
