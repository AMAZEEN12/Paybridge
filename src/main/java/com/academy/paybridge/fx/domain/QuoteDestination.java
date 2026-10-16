package com.academy.paybridge.fx.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Where a quote's money is going, fixed when the quote is made (plan P2). Step B3 adds the bank columns. */
@Entity
@Table(name = "quote_destinations")
public class QuoteDestination {

    @Id
    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(nullable = false, length = 3)
    private String currency;

    protected QuoteDestination() {
    }

    public static QuoteDestination paybridgeAccount(UUID quoteId, String accountNumber, String accountName,
                                                    String currency) {
        QuoteDestination d = new QuoteDestination();
        d.quoteId = quoteId;
        d.kind = "PAYBRIDGE_ACCOUNT";
        d.accountNumber = accountNumber;
        d.accountName = accountName;
        d.currency = currency;
        return d;
    }

    public UUID getQuoteId() { return quoteId; }
    public String getKind() { return kind; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountName() { return accountName; }
    public String getCurrency() { return currency; }
}