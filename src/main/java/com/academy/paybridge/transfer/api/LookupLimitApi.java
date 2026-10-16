package com.academy.paybridge.transfer.api;

/**
 * The per-customer, per-minute limit on lookups that reveal who owns an account (R-LKP-2, R-VER-7).
 * One limiter serves the recipient lookup, the name enquiry and payout verification.
 */
public interface LookupLimitApi {

    /** Counts one lookup for this customer, or throws a 429 ApiException when the minute's limit is used up. */
    void check(Long customerId);
}