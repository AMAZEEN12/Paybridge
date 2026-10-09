package com.academy.paybridge.customer.api;

/** The only door other modules may use to talk to the customer module. */
public interface CustomerApi {

    CustomerView get(Long customerId);

    /**
     * Checks the transaction PIN in its own transaction, so a wrong attempt is counted even though
     * the caller then fails. Throws ApiException (403) for a wrong, missing, or locked PIN.
     */
    void verifyPin(Long customerId, String pin);
}
