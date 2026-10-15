package com.academy.paybridge.account.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The only door other modules may use to talk to the account module. */
public interface AccountApi {

    String FEE_ACCOUNT = "9000000001";
    String VAT_ACCOUNT = "9000000002";
    String STAMP_DUTY_ACCOUNT = "9000000003";

    /**
     * Creates the customer's single account: a wallet in the home currency (flagged as home) plus USD, EUR and GBP
     * wallets, without repeating the home currency. Joins the caller's transaction, so sign-up is all or nothing.
     */
    AccountView openHomeAccount(Long customerId, String homeCurrency);

    /** The customer's single account, created at sign-up. */
    AccountView existingFor(Long customerId);

    AccountView getOwned(String accountNumber, Long customerId);

    AccountView getCustomerAccount(String accountNumber);

    Optional<Long> ownerOf(String accountNumber);

    void lockInOrder(Collection<String> accountNumbers);

    /**
     * Takes money from one wallet. Refused for a frozen account and for a PARKED wallet (R-WAL-6, R-WAL-8).
     * Needs a transaction.
     */
    void debit(String accountNumber, String currency, long minor);

    /** Adds money to one wallet. Allowed for a PARKED wallet: parked wallets can receive (R-WAL-6). Needs a transaction. */
    void credit(String accountNumber, String currency, long minor);

    boolean isFrozen(String accountNumber);

    /**
     * Locks the account row, then both wallets in sorted currency order (R-CONV-4, concern C).
     * Refuses frozen accounts and parked wallets. Needs a transaction.
     */
    void lockForConversion(String accountNumber, String sourceCurrency, String targetCurrency);

    /** Looks up one wallet of an account for reading, or empty if it does not exist. */
    Optional<WalletView> findWallet(String accountNumber, String currency);

    /**
     * The balance of EVERY wallet, in minor units, keyed "<account_number>:<currency>" (the ledger's key shape).
     * Read-only; used by the ledger reconciliation, which groups the keys by currency.
     */
    Map<String, Long> balances();

    /**
     * The currencies a customer account can receive (R-LKP-1): every wallet, active or parked, whose currency is not
     * on the exclusion list, sorted by code. A frozen account is still listed, because only debits are refused.
     * Throws ACCOUNT_NOT_FOUND (404) for an unknown number and for a system account. Read-only.
     */
    List<String> receivableCurrencies(String accountNumber);
}