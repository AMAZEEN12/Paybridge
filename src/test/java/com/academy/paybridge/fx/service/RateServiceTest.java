package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.api.RatesUnavailableException;
import com.academy.paybridge.fx.client.StubRateProvider;
import com.academy.paybridge.fx.domain.RateSnapshot;
import com.academy.paybridge.fx.repository.RateSnapshotRepository;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure tests: stub provider, in-memory repository, a clock the test moves. No database, no network. */
class RateServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");

    private final CurrencyRegistry registry = new CurrencyRegistry(List.of(
            new CurrencyInfo("NGN", "Nigerian naira", "N", 2, true),
            new CurrencyInfo("USD", "US dollar", "$", 2, true),
            new CurrencyInfo("EUR", "Euro", "E", 2, true),
            new CurrencyInfo("GBP", "Pound", "P", 2, true),
            new CurrencyInfo("JPY", "Yen", "Y", 0, true),
            new CurrencyInfo("IRR", "Iranian rial", "IRR", 2, true)),
            List.of("IRR"));

    private MutableClock clock;
    private InMemoryRepo repo;
    private StubRateProvider provider;
    private FxProperties props;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        repo = new InMemoryRepo();
        provider = new StubRateProvider(clock);
        props = new FxProperties(null, null, null, 0, null, null, null, 0);   // all spec defaults
    }

    private RateService service() {
        return new RateService(provider, repo, registry, props, clock);
    }

    @Test
    void crossRateIsTheRatioOfTheUsdRates() {
        RateService svc = service();
        svc.refreshIfDue();
        assertThat(svc.rate("USD", "NGN").rate()).isEqualByComparingTo("1500");
        assertThat(svc.rate("JPY", "NGN").rate()).isEqualByComparingTo("10");
        assertThat(svc.rate("NGN", "USD").rate()).isEqualByComparingTo("0.000666666667");
        assertThat(svc.rate("EUR", "GBP").rate()).isEqualByComparingTo("0.858695652174");
        assertThat(svc.rate("eur", " gbp ").rate()).isEqualByComparingTo("0.858695652174");
    }

    @Test
    void theSnapshotRecordsProviderAndId() {
        RateService svc = service();
        svc.refreshIfDue();
        assertThat(svc.latest()).isPresent();
        assertThat(svc.latest().get().provider()).isEqualTo("stub");
        assertThat(svc.rate("USD", "NGN").snapshot().id()).isPositive();
    }

    @Test
    void startUpDoesNotCallTheProviderWhenTheStoredSnapshotIsFresh() {
        repo.save(snapshot(T0.minus(Duration.ofMinutes(30)), T0.minus(Duration.ofMinutes(30))));
        RateService svc = service();
        svc.onStartup();
        assertThat(provider.fetchCalls()).isZero();
        assertThat(svc.latest()).isPresent();
    }

    @Test
    void startUpRefreshesWhenTheStoredSnapshotIsOlderThanTheRefreshInterval() {
        repo.save(snapshot(T0.minus(Duration.ofHours(2)), T0.minus(Duration.ofHours(2))));
        service().onStartup();
        assertThat(provider.fetchCalls()).isEqualTo(1);
    }

    @Test
    void staleBoundaryIsExactlyTheMaximumAge() {
        RateService svc = service();
        svc.refreshIfDue();
        clock.set(T0.plus(Duration.ofHours(3)));
        assertThat(svc.isStale()).isFalse();
        clock.set(T0.plus(Duration.ofHours(3)).plusSeconds(1));
        assertThat(svc.isStale()).isTrue();
    }

    @Test
    void staleRatesAreRefusedWhenTheProviderIsDown() {          // acceptance 15
        RateService svc = service();
        svc.refreshIfDue();
        clock.set(T0.plus(Duration.ofHours(4)));
        provider.setFailing(true);
        assertThatThrownBy(() -> svc.rate("USD", "NGN")).isInstanceOf(RatesUnavailableException.class);
    }

    @Test
    void staleRatesRecoverThroughOneOnDemandRefresh() {
        RateService svc = service();
        svc.refreshIfDue();
        clock.set(T0.plus(Duration.ofHours(4)));
        assertThat(svc.rate("USD", "NGN").rate()).isEqualByComparingTo("1500");
        assertThat(provider.fetchCalls()).isEqualTo(2);
    }

    @Test
    void onDemandRefreshHonoursTheBackOffAndTheTenMinuteGap() {
        RateService svc = service();
        svc.refreshIfDue();                                       // call 1, succeeds
        clock.set(T0.plus(Duration.ofHours(4)));
        provider.setFailing(true);
        assertThatThrownBy(() -> svc.rate("USD", "NGN")).isInstanceOf(RatesUnavailableException.class);
        assertThat(provider.fetchCalls()).isEqualTo(2);           // failed, back off 5 minutes
        assertThatThrownBy(() -> svc.rate("USD", "NGN")).isInstanceOf(RatesUnavailableException.class);
        assertThat(provider.fetchCalls()).isEqualTo(2);           // inside the back-off
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> svc.rate("USD", "NGN")).isInstanceOf(RatesUnavailableException.class);
        assertThat(provider.fetchCalls()).isEqualTo(2);           // back-off over, but inside the 10-minute gap
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> svc.rate("USD", "NGN")).isInstanceOf(RatesUnavailableException.class);
        assertThat(provider.fetchCalls()).isEqualTo(3);
    }

    @Test
    void backgroundRetriesBackOffLongerAfterEachFailure() {
        provider.setFailing(true);
        RateService svc = service();
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(1);           // next try in 5 minutes
        clock.advance(Duration.ofMinutes(4));
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(2);           // next try in 10 minutes
        clock.advance(Duration.ofMinutes(9));
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(1));
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(3);
    }

    @Test
    void refreshingStopsAtNinetyFivePercentOfTheQuota() {
        provider.setUsage(950, 1000);
        RateService svc = service();
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isZero();
        assertThat(svc.latest()).isEmpty();
        provider.setUsage(100, 1000);                             // a new month
        clock.advance(Duration.ofMinutes(61));
        svc.refreshIfDue();
        assertThat(provider.fetchCalls()).isEqualTo(1);
    }

    @Test
    void aSnapshotWithoutUsdIsRejected() {
        provider = new StubRateProvider(clock, Map.of("NGN", new BigDecimal("1500")));
        RateService svc = service();
        svc.refreshIfDue();
        assertThat(svc.latest()).isEmpty();
        assertThat(svc.isStale()).isTrue();
    }

    @Test
    void aCurrencyWithoutARateIsRefused() {
        RateService svc = service();
        svc.refreshIfDue();
        assertThatThrownBy(() -> svc.rate("USD", "IRR")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> svc.rate("USD", "XXX")).isInstanceOf(ApiException.class);
    }

    @Test
    void twoSimultaneousRefreshesCallTheProviderOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RateProvider slow = new RateProvider() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public ProviderRates fetch(Collection<String> codes) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ProviderRates(clock.instant(),
                        Map.of("USD", BigDecimal.ONE, "NGN", new BigDecimal("1500")));
            }

            @Override
            public ProviderUsage usage() {
                return new ProviderUsage(0, 1000);
            }
        };
        RateService svc = new RateService(slow, repo, registry, props, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> first = pool.submit(svc::refreshIfDue);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        svc.refreshIfDue();                                       // second caller returns at once
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(calls.get()).isEqualTo(1);
    }

    private static RateSnapshot snapshot(Instant providerTimestamp, Instant fetchedAt) {
        return new RateSnapshot(null, "stub", "USD", providerTimestamp, fetchedAt,
                Map.of("USD", new BigDecimal("1.000000000000"), "NGN", new BigDecimal("1500.000000000000")));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class InMemoryRepo implements RateSnapshotRepository {
        private final List<RateSnapshot> saved = new ArrayList<>();

        @Override
        public synchronized RateSnapshot save(RateSnapshot snapshot) {
            RateSnapshot withId = snapshot.withId(saved.size() + 1L);
            saved.add(withId);
            return withId;
        }

        @Override
        public synchronized Optional<RateSnapshot> findLatest() {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(saved.size() - 1));
        }
    }
}