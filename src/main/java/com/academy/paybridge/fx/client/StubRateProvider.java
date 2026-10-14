package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.api.RateProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed rates, no network. The default provider locally and in tests, so the free quota is never touched. */
public class StubRateProvider implements RateProvider {

    private final Clock clock;
    private final Map<String, BigDecimal> rates;
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private volatile boolean failing;
    private volatile long usedRequests;
    private volatile long quota = 1000;

    public StubRateProvider(Clock clock) {
        this(clock, Map.of(
                "USD", new BigDecimal("1"),
                "NGN", new BigDecimal("1500"),
                "EUR", new BigDecimal("0.92"),
                "GBP", new BigDecimal("0.79"),
                "JPY", new BigDecimal("150"),
                "KWD", new BigDecimal("0.307"),
                "CAD", new BigDecimal("1.36"),
                "GHS", new BigDecimal("15"),
                "ZAR", new BigDecimal("18")));
    }

    public StubRateProvider(Clock clock, Map<String, BigDecimal> rates) {
        this.clock = clock;
        this.rates = Map.copyOf(rates);
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public ProviderRates fetch(Collection<String> codes) {
        fetchCalls.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("stub provider is set to fail");
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String code : codes) {
            if (rates.containsKey(code)) {
                result.put(code, rates.get(code));
            }
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return new ProviderRates(now, result);
    }

    @Override
    public ProviderUsage usage() {
        return new ProviderUsage(usedRequests, quota);
    }

    public int fetchCalls() {
        return fetchCalls.get();
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void setUsage(long usedRequests, long quota) {
        this.usedRequests = usedRequests;
        this.quota = quota;
    }
}