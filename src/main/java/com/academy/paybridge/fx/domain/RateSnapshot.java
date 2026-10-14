package com.academy.paybridge.fx.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One stored fetch of rates (USD base). rates maps a currency code to units of that currency per 1 USD. */
public record RateSnapshot(Long id, String provider, String baseCurrency, Instant providerTimestamp,
                           Instant fetchedAt, Map<String, BigDecimal> rates) {

    public RateSnapshot {
        rates = Map.copyOf(rates);
    }

    public RateSnapshot withId(long newId) {
        return new RateSnapshot(newId, provider, baseCurrency, providerTimestamp, fetchedAt, rates);
    }
}