package com.academy.paybridge.account.api;

import java.util.Collection;
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

    AccountView getOwned(String accountNumber, Long customerId);

    AccountView getCustomerAccount(String accountNumber);

    Optional<Long> ownerOf(String accountNumber);

    void lockInOrder(Collection<String> accountNumbers);

    void debit(String accountNumber, long kobo);

    void credit(String accountNumber, long kobo);

    boolean isFrozen(String accountNumber);

    /**
     * The balance of EVERY wallet, in minor units, keyed "<account_number>:<currency>" (the ledger's key shape).
     * Read-only; used by the ledger reconciliation, which groups the keys by currency.
     */
    Map<String, Long> balances();
}