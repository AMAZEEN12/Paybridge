package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.RateApi;
import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.api.RatesUnavailableException;
import com.academy.paybridge.fx.domain.RateSnapshot;
import com.academy.paybridge.fx.repository.RateSnapshotRepository;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the newest rate snapshot in memory and keeps it fresh without wasting the free quota.
 * Nothing here runs inside a database transaction: the provider is called first, and the repository saves the
 * result in its own short transaction (R-RATE-7).
 */
@Service
public class RateService implements RateApi {

    private static final Logger log = LoggerFactory.getLogger(RateService.class);

    private static final String BASE = "USD";
    private static final int RATE_SCALE = 12;
    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final RateProvider provider;
    private final RateSnapshotRepository repository;
    private final CurrencyRegistry currencies;
    private final FxProperties props;
    private final Clock clock;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile RateSnapshot latest;
    private volatile Instant lastAttempt = Instant.EPOCH;
    private volatile Instant retryNotBefore = Instant.EPOCH;
    private volatile int failures;

    public RateService(RateProvider provider, RateSnapshotRepository repository, CurrencyRegistry currencies,
                       FxProperties props, Clock clock) {
        this.provider = provider;
        this.repository = repository;
        this.currencies = currencies;
        this.props = props;
        this.clock = clock;
    }

    /** Load the stored snapshot; call the provider only if it is older than the refresh interval (R-RATE-4). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            latest = repository.findLatest().orElse(null);
            refreshIfDue();
        } catch (RuntimeException e) {
            log.warn("Could not load exchange rates at start-up: {}", e.getClass().getSimpleName());
        }
    }

    /** Called by the scheduled job. Refreshes when our last successful fetch is older than the refresh interval. */
    public void refreshIfDue() {
        RateSnapshot current = latest;
        if (current != null
                && Duration.between(current.fetchedAt(), clock.instant()).compareTo(props.refreshInterval()) < 0) {
            return;
        }
        refresh(false);
    }

    @Override
    public Optional<SnapshotInfo> latest() {
        RateSnapshot snapshot = latest;
        return snapshot == null ? Optional.empty() : Optional.of(info(snapshot));
    }

    @Override
    public boolean isStale() {
        return isStale(latest);
    }

    @Override
    public RateResult rate(String from, String to) {
        String a = normalise(from);
        String b = normalise(to);
        RateSnapshot snapshot = latest;
        if (isStale(snapshot)) {
            refresh(true);
            snapshot = latest;
            if (isStale(snapshot)) {
                throw new RatesUnavailableException();
            }
        }
        BigDecimal rateA = snapshot.rates().get(a);
        BigDecimal rateB = snapshot.rates().get(b);
        if (rateA == null || rateB == null) {
            throw ApiException.badRequest("NO_RATE", "No exchange rate is available for " + a + " to " + b + ".");
        }
        return new RateResult(rateB.divide(rateA, RATE_SCALE, RoundingMode.HALF_EVEN), info(snapshot));
    }

    private boolean isStale(RateSnapshot snapshot) {
        return snapshot == null
                || Duration.between(snapshot.providerTimestamp(), clock.instant()).compareTo(props.maxSnapshotAge()) > 0;
    }

    /** One refresh at a time (single-flight), no sooner than the back-off, and on-demand ones no more than every 10 minutes. */
    private boolean refresh(boolean onDemand) {
        Instant now = clock.instant();
        if (now.isBefore(retryNotBefore)) {
            return false;
        }
        if (onDemand && now.isBefore(lastAttempt.plus(props.onDemandRefreshMinGap()))) {
            return false;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return false;
        }
        try {
            lastAttempt = now;
            if (quotaReached(now)) {
                return false;
            }
            RateProvider.ProviderRates fetched = provider.fetch(supportedCodes());
            latest = repository.save(toSnapshot(fetched));
            failures = 0;
            retryNotBefore = Instant.EPOCH;
            log.info("Stored exchange rate snapshot {} with {} currencies", latest.id(), latest.rates().size());
            return true;
        } catch (RuntimeException e) {
            failures++;
            Duration wait = backoff(failures);
            retryNotBefore = clock.instant().plus(wait);
            log.warn("Rate refresh failed ({}: {}). Next try in {} minutes.",
                    e.getClass().getSimpleName(), e.getMessage(), wait.toMinutes());
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    private boolean quotaReached(Instant now) {
        try {
            RateProvider.ProviderUsage usage = provider.usage();
            if (usage.percentUsed() >= props.quotaStopPercent()) {
                log.warn("Rate provider quota is at {}% ({} of {} requests). Refreshing is paused.",
                        usage.percentUsed(), usage.requests(), usage.quota());
                retryNotBefore = now.plus(MAX_BACKOFF);
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("Rate provider usage check failed: {}", e.getMessage());
        }
        return false;
    }

    private List<String> supportedCodes() {
        TreeSet<String> codes = new TreeSet<>();
        for (CurrencyInfo info : currencies.usable()) {
            codes.add(info.code());
        }
        codes.add(BASE);
        return new ArrayList<>(codes);
    }

    private RateSnapshot toSnapshot(RateProvider.ProviderRates fetched) {
        Map<String, BigDecimal> clean = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (String code : supportedCodes()) {
            BigDecimal rate = fetched.rates().get(code);
            if (rate == null) {
                missing.add(code);
                continue;
            }
            if (rate.signum() <= 0) {
                throw new IllegalStateException("Provider sent a rate that is not positive for " + code);
            }
            clean.put(code, rate.setScale(RATE_SCALE, RoundingMode.HALF_EVEN));
        }
        BigDecimal usd = clean.get(BASE);
        if (usd == null || usd.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalStateException("Provider snapshot has no USD rate of exactly 1");
        }
        if (!missing.isEmpty()) {
            log.warn("Provider had no rate for these supported currencies: {}", missing);
        }
        return new RateSnapshot(null, provider.name(), BASE, fetched.providerTimestamp(), clock.instant(), clean);
    }

    /** 5, 10, 20, 40 minutes, then 1 hour at most (R-RATE-8). */
    private static Duration backoff(int failureCount) {
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(failureCount - 1, 5));
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    private static SnapshotInfo info(RateSnapshot s) {
        return new SnapshotInfo(s.id(), s.provider(), s.providerTimestamp(), s.fetchedAt());
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}