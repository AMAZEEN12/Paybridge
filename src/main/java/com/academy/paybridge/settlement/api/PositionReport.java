package com.academy.paybridge.settlement.api;

import java.time.Instant;
import java.util.List;

/**
 * The position report (R-SET-1, R-SET-2). A position is credits minus debits from the ledger; it is never stored or
 * typed in. {@code warnings} lists every position below its configured minimum. Nothing here ever blocks a customer.
 */
public record PositionReport(Instant generatedAt, List<CurrencyPositions> currencies, List<String> warnings) {

    public record CurrencyPositions(String currency, List<Position> positions) {
    }

    /**
     * @param netMinor        credits minus debits, in the currency's minor units
     * @param thresholdMinor  the configured minimum, or null when none is set
     * @param belowThreshold  true when a minimum is set and the position is under it
     */
    public record Position(String name, String account, long netMinor, String display,
                           Long thresholdMinor, boolean belowThreshold) {
    }
}