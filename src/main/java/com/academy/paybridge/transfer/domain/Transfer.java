package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
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

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "source_account_number", nullable = false, updatable = false)
    private String sourceAccountNumber;

    @Column(name = "destination_account_number", nullable = false, updatable = false)
    private String destinationAccountNumber;

    @Column(name = "destination_bank_code", updatable = false)
    private String destinationBankCode;

    @Column(name = "destination_bank_name", updatable = false)
    private String destinationBankName;

    @Column(name = "destination_account_name", updatable = false)
    private String destinationAccountName;

    @Column(name = "recipient_code", updatable = false)
    private String recipientCode;

    @Column(name = "amount_kobo", nullable = false, updatable = false)
    private long amountKobo;

    @Column(name = "fee_kobo", nullable = false, updatable = false)
    private long feeKobo;

    @Column(name = "vat_kobo", nullable = false, updatable = false)
    private long vatKobo;

    @Column(name = "stamp_duty_kobo", nullable = false, updatable = false)
    private long stampDutyKobo;

    @Column(name = "total_debit_kobo", nullable = false, updatable = false)
    private long totalDebitKobo;

    @Column(updatable = false)
    private String narration;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    private String note;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
    }

    public Transfer(String reference, TransferType type, TransferStatus status, String source, String destination,
                    long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo, String narration,
                    String idempotencyKey, String requestHash, Instant now) {
        this.reference = reference;
        this.type = type;
        this.status = status;
        this.sourceAccountNumber = source;
        this.destinationAccountNumber = destination;
        this.amountKobo = amountKobo;
        this.feeKobo = feeKobo;
        this.vatKobo = vatKobo;
        this.stampDutyKobo = stampDutyKobo;
        this.totalDebitKobo = amountKobo + feeKobo + vatKobo + stampDutyKobo;
        this.narration = narration;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Bank details for an external payout. */
    public void setBank(String bankCode, String bankName, String accountName, String recipientCode) {
        this.destinationBankCode = bankCode;
        this.destinationBankName = bankName;
        this.destinationAccountName = accountName;
        this.recipientCode = recipientCode;
    }

    public long chargesKobo() {
        return feeKobo + vatKobo + stampDutyKobo;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public TransferType getType() { return type; }
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }
    public String getSourceAccountNumber() { return sourceAccountNumber; }
    public String getDestinationAccountNumber() { return destinationAccountNumber; }
    public String getDestinationBankCode() { return destinationBankCode; }
    public String getDestinationBankName() { return destinationBankName; }
    public String getDestinationAccountName() { return destinationAccountName; }
    public String getRecipientCode() { return recipientCode; }
    public long getAmountKobo() { return amountKobo; }
    public long getFeeKobo() { return feeKobo; }
    public long getVatKobo() { return vatKobo; }
    public long getStampDutyKobo() { return stampDutyKobo; }
    public long getTotalDebitKobo() { return totalDebitKobo; }
    public String getNarration() { return narration; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String gatewayReference) { this.gatewayReference = gatewayReference; }
    public String getNote() { return note; }
    public void setNote(String note) {
        this.note = note == null ? null : (note.length() > 300 ? note.substring(0, 300) : note);
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
