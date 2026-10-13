package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.CurrencyTotals;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationRules;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests: ledger rows and balances in, a report out. Amounts are minor units of each currency. */
class ReconciliationByCurrencyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Object[]> NONE = List.of();

    private final CurrencyRegistry registry = new CurrencyRegistry(List.of(
            new CurrencyInfo("USD", "US dollar", "$", 2, true),
            new CurrencyInfo("EUR", "Euro", "\u20ac", 2, true)), List.of());

    private static Object[] row(Object... values) {
        return values;
    }

    @Test
    void aJournalInTwoCurrenciesBalancesPerCurrency() {
        // 10.00 USD arrived and sits in the FX pool; 5.00 EUR left the pool towards in-flight. Each currency adds up alone.
        List<Object[]> ledger = List.of(
                row(LedgerApi.EXT_FUNDING, "USD", -1_000L),
                row("EXT:FX_POOL:USD", "USD", 1_000L),
                row("EXT:FX_POOL:EUR", "EUR", -500L),
                row(LedgerApi.EXT_IN_FLIGHT, "EUR", 500L));

        ReconciliationReport r = ReconciliationRules.checkByCurrency(NOW, 4, 1, NONE, ledger, Map.of(), registry);

        assertThat(r.ok()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.currencies()).extracting(CurrencyTotals::currency).containsExactly("EUR", "NGN", "USD");
        assertThat(r.currencies()).filteredOn(t -> t.currency().equals("USD"))
                .singleElement().satisfies(t -> assertThat(t.fundedMinor()).isEqualTo(1_000L));
    }

    @Test
    void anUnbalancedSecondCurrencyIsReportedWithItsCode() {
        List<Object[]> unbalanced = List.<Object[]>of(row("CONV:1", "EUR", 500L, 400L));

        ReconciliationReport r = ReconciliationRules.checkByCurrency(NOW, 4, 1, unbalanced, List.of(), Map.of(), registry);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.startsWith("[EUR]") && p.contains("CONV:1")
                && p.contains("does not balance") && p.contains("\u20ac5.00") && p.contains("\u20ac4.00"));
    }

    @Test
    void aWalletThatDisagreesWithTheLedgerIsReportedPerCurrencyWithoutTheSuffix() {
        List<Object[]> ledger = List.of(row(LedgerApi.EXT_FUNDING, "USD", -1_000L), row("2000000001:USD", "USD", 1_000L));
        Map<String, Long> balances = Map.of("2000000001:USD", 900L);

        ReconciliationReport r = ReconciliationRules.checkByCurrency(NOW, 2, 1, NONE, ledger, balances, registry);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.equals("[USD] Account ****0001: balance $9.00 but ledger says $10.00"));
    }

    @Test
    void aWalletWithMoneyButNoLedgerLinesIsCaught() {
        ReconciliationReport r = ReconciliationRules.checkByCurrency(
                NOW, 0, 0, NONE, List.of(), Map.of("2000000001:USD", 500L), registry);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.startsWith("[USD]") && p.contains("ledger says"));
    }

    @Test
    void ledgerLinesForAWalletThatDoesNotExistAreCaught() {
        List<Object[]> ledger = List.<Object[]>of(row("2000000009:EUR", "EUR", 100L));

        ReconciliationReport r = ReconciliationRules.checkByCurrency(NOW, 1, 1, NONE, ledger, Map.of(), registry);

        assertThat(r.problems()).anyMatch(p -> p.startsWith("[EUR]") && p.contains("unknown account")
                && p.contains("****0009") && !p.contains(":EUR"));
    }

    @Test
    void nairaOnlyDataGivesTheSameAnswerAsBefore() {
        Map<String, Long> net = Map.of(
                LedgerApi.EXT_FUNDING, -100_000L, LedgerApi.EXT_PAID_OUT, 20_000L, LedgerApi.EXT_IN_FLIGHT, 5_000L,
                "2000000001:NGN", 74_000L, "9000000001:NGN", 1_000L);
        Map<String, Long> balances = Map.of("2000000001:NGN", 74_000L, "9000000001:NGN", 1_000L);
        List<Object[]> rows = net.entrySet().stream().map(e -> row(e.getKey(), "NGN", e.getValue())).toList();

        ReconciliationReport old = ReconciliationRules.check(NOW, 8, 4, NONE, net, balances);
        ReconciliationReport now = ReconciliationRules.checkByCurrency(NOW, 8, 4, NONE, rows, balances, registry);

        assertThat(now.ok()).isTrue();
        assertThat(now.problems()).isEqualTo(old.problems());
        assertThat(now.fundedKobo()).isEqualTo(old.fundedKobo()).isEqualTo(100_000L);
        assertThat(now.heldKobo()).isEqualTo(old.heldKobo()).isEqualTo(75_000L);
        assertThat(now.inFlightKobo()).isEqualTo(5_000L);
        assertThat(now.paidOutKobo()).isEqualTo(20_000L);
    }

    @Test
    void aBrokenNairaCheckStillFailsWithoutAPrefix() {
        List<Object[]> ledger = List.of(row(LedgerApi.EXT_FUNDING, "NGN", -10_000L), row("2000000001:NGN", "NGN", 10_000L));
        Map<String, Long> balances = Map.of("2000000001:NGN", 9_000L);

        ReconciliationReport r = ReconciliationRules.checkByCurrency(NOW, 2, 1, NONE, ledger, balances, registry);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.startsWith("Account ****0001") && p.contains("ledger says"));
    }
}