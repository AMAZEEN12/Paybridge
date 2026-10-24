package com.academy.paybridge.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** The account details issued for one wallet, so money can be sent straight into it. Sandbox: simulated. */
@Entity
@Table(name = "wallet_funding_details")
public class FundingDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Column(name = "bank_name", nullable = false, updatable = false, length = 80)
    private String bankName;

    @Column(name = "bank_country", nullable = false, updatable = false, length = 2)
    private String bankCountry;

    @Column(name = "account_number", nullable = false, updatable = false, length = 34)
    private String accountNumber;

    @Column(name = "routing_label", updatable = false, length = 30)
    private String routingLabel;

    @Column(name = "routing_value", updatable = false, length = 20)
    private String routingValue;

    @Column(updatable = false, length = 34)
    private String iban;

    @Column(name = "swift_bic", updatable = false, length = 11)
    private String swiftBic;

    @Column(nullable = false, updatable = false, length = 20)
    private String reference;

    @Column(nullable = false, updatable = false)
    private boolean simulated = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FundingDetails() {
    }

    public FundingDetails(Wallet wallet, String bankName, String bankCountry, String accountNumber,
                          String routingLabel, String routingValue, String iban, String swiftBic,
                          String reference) {
        this.wallet = wallet;
        this.bankName = bankName;
        this.bankCountry = bankCountry;
        this.accountNumber = accountNumber;
        this.routingLabel = routingLabel;
        this.routingValue = routingValue;
        this.iban = iban;
        this.swiftBic = swiftBic;
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }
    public Wallet getWallet() { return wallet; }
    public String getBankName() { return bankName; }
    public String getBankCountry() { return bankCountry; }
    public String getAccountNumber() { return accountNumber; }
    public String getRoutingLabel() { return routingLabel; }
    public String getRoutingValue() { return routingValue; }
    public String getIban() { return iban; }
    public String getSwiftBic() { return swiftBic; }
    public String getReference() { return reference; }
    public boolean isSimulated() { return simulated; }
    public Instant getCreatedAt() { return createdAt; }
}