package com.academy.paybridge.fx.api;

import java.time.Instant;

/** Payout totals for the shared daily limit (R-LIM2-3). */
public interface QuoteTotalsApi {

    /** USD cents of the customer's payouts that are EXECUTING or COMPLETED since {@code since}. */
    long payoutUsdSince(Long customerId, Instant since);
}