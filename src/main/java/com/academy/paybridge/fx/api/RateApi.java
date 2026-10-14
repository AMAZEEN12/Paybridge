package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** The only door other modules may use to read exchange rates. */
public interface RateApi {

    Optional<SnapshotInfo> latest();

    /** True when there is no snapshot or the newest one is older than max-snapshot-age (R-RATE-6). */
    boolean isStale();

    /**
     * Rate from one currency to another, derived as R[to] / R[from] (R-RATE-5). If the snapshot is stale this tries one
     * on-demand refresh. Throws RatesUnavailableException if the rates are still stale.
     * Call it BEFORE opening a database transaction: the refresh makes an HTTP call.
     */
    RateResult rate(String from, String to);

    record SnapshotInfo(long id, String provider, Instant providerTimestamp, Instant fetchedAt) { }

    record RateResult(BigDecimal rate, SnapshotInfo snapshot) { }

    /**
     * The rate from one currency to another AND from the first currency to USD, both derived from the SAME snapshot,
     * so a refresh between two calls can never mix two snapshots in one quote. Same rules as rate(): call it BEFORE
     * opening a database transaction.
     */
    RateWithUsd rateWithUsd(String from, String to);

    record RateWithUsd(BigDecimal rate, BigDecimal toUsd, SnapshotInfo snapshot) { }
}