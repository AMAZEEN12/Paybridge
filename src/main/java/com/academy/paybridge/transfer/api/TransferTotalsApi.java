package com.academy.paybridge.transfer.api;

import java.time.Instant;

/** The sender's non-NGN transfer total, for the shared daily limit that payouts also use (R-LIM2-3). */
public interface TransferTotalsApi {

    /** USD cents sent from this account by transfers that did not fail, since {@code since}. */
    long usdSentSince(String sourceAccountNumber, Instant since);
}