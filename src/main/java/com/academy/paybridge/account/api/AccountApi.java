package com.academy.paybridge.account.api;

import java.util.Collection;
import java.util.Optional;

/** The only door other modules may use to talk to the account module. */
public interface AccountApi {

    String FEE_ACCOUNT = "9000000001";
    String VAT_ACCOUNT = "9000000002";
    String STAMP_DUTY_ACCOUNT = "9000000003";


    AccountView getOwned(String accountNumber, Long customerId);

    AccountView getCustomerAccount(String accountNumber);

    Optional<Long> ownerOf(String accountNumber);

    void lockInOrder(Collection<String> accountNumbers);


    void debit(String accountNumber, long kobo);

    void credit(String accountNumber, long kobo);

    boolean isFrozen(String accountNumber);
}
