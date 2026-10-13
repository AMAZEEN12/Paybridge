package com.academy.paybridge.ledger.api;

/**
 * The reconciliation totals for one currency, in that currency's minor units. For every currency:
 * held + inFlight + paidOut + FX pool and fee accounts = funded.
 */
public record CurrencyTotals(String currency, long fundedMinor, long paidOutMinor, long inFlightMinor, long heldMinor) {
}