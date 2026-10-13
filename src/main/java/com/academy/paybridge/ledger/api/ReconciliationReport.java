package com.academy.paybridge.ledger.api;

import java.time.Instant;
import java.util.List;

/**
 * The result of one reconciliation run. ok = true only when problems is empty.
 *
 * fundedKobo   money that came in from outside (opening balances plus funding)
 * paidOutKobo  money the provider confirmed as paid to other banks
 * inFlightKobo reserved payouts not yet confirmed
 * heldKobo     the sum of every PayBridge account balance (customers and charge accounts)
 *
 * The rule that must always hold:  heldKobo + inFlightKobo + paidOutKobo = fundedKobo
 *
 * The *Kobo totals are the Naira totals. problems covers every currency (a line for another currency starts with its
 * code in brackets, for example "[USD]"), and currencies has the totals of each currency in its own minor units.
 */
public record ReconciliationReport(
        Instant checkedAt,
        boolean ok,
        long entries,
        long journals,
        long fundedKobo,
        long paidOutKobo,
        long inFlightKobo,
        long heldKobo,
        List<String> problems,
        List<CurrencyTotals> currencies) {

    /** The report as it was before currencies: the Naira totals only. */
    public ReconciliationReport(Instant checkedAt, boolean ok, long entries, long journals, long fundedKobo,
                                long paidOutKobo, long inFlightKobo, long heldKobo, List<String> problems) {
        this(checkedAt, ok, entries, journals, fundedKobo, paidOutKobo, inFlightKobo, heldKobo, problems, List.of());
    }
}