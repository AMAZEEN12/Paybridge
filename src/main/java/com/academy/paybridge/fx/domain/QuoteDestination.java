
package com.academy.paybridge.fx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The destination locked into a quote.
 * Supports both PayBridge account transfers and external bank payouts.
 */
@Entity
@Table(name = "quote_destinations")
public class QuoteDestination {

    @Id
    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @Column(name = "account_name", nullable = false, length = 140)
    private String accountName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "bank_code", length = 20)
    private String bankCode;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(length = 2)
    private String country;

    @Column(name = "bank_fields", columnDefinition = "text")
    private String bankFields;

    @Column(name = "recipient_type", length = 20)
    private String recipientType;

    @Column(length = 30)
    private String purpose;

    @Column(name = "verification_source", length = 20)
    private String verificationSource;

    @Column(name = "rate_mode", length = 20)
    private String rateMode;

    @Column(name = "provider_rate_ref", length = 100)
    private String providerRateRef;

    @Column(name = "provider_rate_expires_at")
    private Instant providerRateExpiresAt;

    protected QuoteDestination() {
    }

    public static QuoteDestination paybridgeAccount(
            UUID quoteId,
            String accountNumber,
            String accountName,
            String currency) {

        QuoteDestination destination = new QuoteDestination();
        destination.quoteId = quoteId;
        destination.kind = "PAYBRIDGE_ACCOUNT";
        destination.accountNumber = accountNumber;
        destination.accountName = accountName;
        destination.currency = currency;
        return destination;
    }

    public static QuoteDestination bankAccount(
            UUID quoteId,
            String accountNumber,
            String accountName,
            String currency,
            String bankCode,
            String bankName,
            String country,
            String bankFields,
            String recipientType,
            String purpose,
            String verificationSource,
            String rateMode,
            String providerRateRef,
            Instant providerRateExpiresAt) {

        QuoteDestination destination = new QuoteDestination();
        destination.quoteId = quoteId;
        destination.kind = "BANK_ACCOUNT";
        destination.accountNumber = accountNumber;
        destination.accountName = accountName;
        destination.currency = currency;
        destination.bankCode = bankCode;
        destination.bankName = bankName;
        destination.country = country;
        destination.bankFields = bankFields;
        destination.recipientType = recipientType;
        destination.purpose = purpose;
        destination.verificationSource = verificationSource;
        destination.rateMode = rateMode;
        destination.providerRateRef = providerRateRef;
        destination.providerRateExpiresAt = providerRateExpiresAt;
        return destination;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public String getKind() {
        return kind;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getCurrency() {
        return currency;
    }

    public String getBankCode() {
        return bankCode;
    }

    public String getBankName() {
        return bankName;
    }

    public String getCountry() {
        return country;
    }

    public String getBankFields() {
        return bankFields;
    }

    public String getRecipientType() {
        return recipientType;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getVerificationSource() {
        return verificationSource;
    }

    public String getRateMode() {
        return rateMode;
    }

    public String getProviderRateRef() {
        return providerRateRef;
    }

    public Instant getProviderRateExpiresAt() {
        return providerRateExpiresAt;
    }
}
