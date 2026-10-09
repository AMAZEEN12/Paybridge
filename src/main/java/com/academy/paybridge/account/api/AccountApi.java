package com.academy.paybridge.account.api;

import java.util.Collection;
import java.util.Optional;

/** The only door other modules may use to talk to the account module. */
public interface AccountApi {

    String FEE_ACCOUNT = "9000000001";
    String VAT_ACCOUNT = "9000000002";
    String STAMP_DUTY_ACCOUNT = "9000000003";

    /** The account if it exists AND belongs to this customer. Otherwise 404, so nobody can probe for accounts. */
    AccountView getOwned(String accountNumber, Long customerId);

    /** Any customer account (never a system account). 404 if it does not exist. */
    AccountView getCustomerAccount(String accountNumber);

    Optional<Long> ownerOf(String accountNumber);

    /**
     * Locks these rows (SELECT ... FOR UPDATE) in ascending account-number order, the same order for
     * everyone, so two transfers in opposite directions can never deadlock. Must run inside a transaction.
     */
    void lockInOrder(Collection<String> accountNumbers);

    /** Takes money out. Needs an active transaction. Fails if frozen or if the balance is too low. */
    void debit(String accountNumber, long kobo);

    /** Puts money in. Needs an active transaction. Frozen accounts can still receive. */
    void credit(String accountNumber, long kobo);

    boolean isFrozen(String accountNumber);
}
