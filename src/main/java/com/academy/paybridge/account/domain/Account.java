package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /** Balances live in wallets. Eager on purpose: the web layer reads them outside a transaction. */
    @OneToMany(mappedBy = "account", fetch = FetchType.EAGER, cascade = CascadeType.PERSIST)
    private List<Wallet> wallets = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private AccountType type = AccountType.CUSTOMER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** Second safety net behind the row lock: a stale update fails instead of overwriting. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Account() {
    }

    public Account(String accountNumber, Long customerId) {
        this.accountNumber = accountNumber;
        this.customerId = customerId;
    }

    public Long getId() {
        return id;
    }
    public String getAccountNumber() {
        return accountNumber;
    }
    public Long getCustomerId() {
        return customerId;
    }
    public List<Wallet> getWallets() {
        return wallets;
    }
    public void addWallet(Wallet wallet) {
        wallets.add(wallet);
    }
    /** Derived from the NGN wallet (zero if there is none). Kept so older callers and tests still compile. */
    public long getBalanceKobo() {
        return wallets.stream()
                .filter(w -> Wallet.NGN.equals(w.getCurrency()))
                .mapToLong(Wallet::getBalanceMinor)
                .findFirst()
                .orElse(0L);
    }
    public AccountType getType() {
        return type;
    }
    public AccountStatus getStatus() {
        return status;
    }
    public void setStatus(AccountStatus status) {
        this.status = status;
    }
    public long getVersion() {
        return version;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
}