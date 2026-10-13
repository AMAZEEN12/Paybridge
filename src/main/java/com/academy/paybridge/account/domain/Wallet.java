package com.academy.paybridge.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** One currency's balance inside an account. Step 1 only ever creates NGN wallets. */
@Entity
@Table(name = "wallets")
public class Wallet {

    public static final String NGN = "NGN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "is_home", nullable = false, updatable = false)
    private boolean home;

    /** Second safety net behind the row lock, like on Account. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Wallet() {
    }

    public Wallet(Account account, String currency, boolean home) {
        this.account = account;
        this.currency = currency;
        this.home = home;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public void setBalanceMinor(long balanceMinor) { this.balanceMinor = balanceMinor; }
    public WalletStatus getStatus() { return status; }
    public void setStatus(WalletStatus status) { this.status = status; }
    public boolean isHome() { return home; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}