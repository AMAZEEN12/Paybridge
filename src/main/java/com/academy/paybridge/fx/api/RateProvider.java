package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Hides where rates come from. Swapping providers is a configuration change (R-RATE-1). */
public interface RateProvider {

    String name();

    /** Rates for the given codes, as units of each currency per 1 USD. Throws a RuntimeException on any failure. */
    ProviderRates fetch(Collection<String> codes);

    /** How much of the monthly request quota is used. Must not cost a rate request. */
    ProviderUsage usage();

    record ProviderRates(Instant providerTimestamp, Map<String, BigDecimal> rates) { }

    record ProviderUsage(long requests, long quota) {
        public int percentUsed() {
            return quota <= 0 ? 0 : (int) Math.min(100L, requests * 100L / quota);
        }
    }
}