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

/** One line of a journal. Append-only: the database refuses updates and deletes. */
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

    @Column(name = "amount_kobo", nullable = false, updatable = false)
    private long amountKobo;

    @Column(name = "entry_type", nullable = false, updatable = false)
    private String entryType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String journalKey, String reference, String accountNumber, Direction direction,
                       long amountKobo, String entryType, Instant now) {
        this.journalKey = journalKey;
        this.reference = reference;
        this.accountNumber = accountNumber;
        this.direction = direction;
        this.amountKobo = amountKobo;
        this.entryType = entryType;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public String getJournalKey() { return journalKey; }
    public String getReference() { return reference; }
    public String getAccountNumber() { return accountNumber; }
    public Direction getDirection() { return direction; }
    public long getAmountKobo() { return amountKobo; }
    public String getEntryType() { return entryType; }
    public Instant getCreatedAt() { return createdAt; }
}