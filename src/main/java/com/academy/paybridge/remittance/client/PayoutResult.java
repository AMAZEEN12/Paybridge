package com.academy.paybridge.remittance.client;

/**
 * What the partner reports about a payout.
 * {@code convertedNotPaid}: the partner converted the money but did not pay it. It matters only when retries are on
 * (S16, step B5); with retries off a failure is simply a failure and the customer is refunded.
 * {@code realSourceCostMinor}: the partner's real cost in the source currency once it is known (OWN_LOCK, journal J6),
 * otherwise null.
 */
public record PayoutResult(PayoutStatus status, String providerRef, String message,
                           boolean convertedNotPaid, Long realSourceCostMinor) {

    public static PayoutResult of(PayoutStatus status, String providerRef, String message) {
        return new PayoutResult(status, providerRef, message, false, null);
    }
}