package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
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
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "balance_kobo", nullable = false)
    private long balanceKobo;

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
    public long getBalanceKobo() {
        return balanceKobo;
    }
    public void setBalanceKobo(long balanceKobo) {
        this.balanceKobo = balanceKobo;
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
