package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests: no database, no Spring. Numbers are in kobo. */
class ReconciliationRulesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Object[]> NONE = List.of();

    @Test
    void aHealthyLedgerPasses() {
        // 100,000 funded. Ada paid 20,000 out (+ 1,000 fee already collected), 5,000 is still in flight.
        Map<String, Long> ledger = Map.of(
                LedgerApi.EXT_FUNDING, -100_000L,
                LedgerApi.EXT_PAID_OUT, 20_000L,
                LedgerApi.EXT_IN_FLIGHT, 5_000L,
                "2000000001", 74_000L,
                "9000000001", 1_000L);
        Map<String, Long> balances = Map.of("2000000001", 74_000L, "9000000001", 1_000L);

        ReconciliationReport r = ReconciliationRules.check(NOW, 8, 4, NONE, ledger, balances);

        assertThat(r.ok()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.fundedKobo()).isEqualTo(100_000L);
        assertThat(r.heldKobo()).isEqualTo(75_000L);
        assertThat(r.inFlightKobo()).isEqualTo(5_000L);
    }

    @Test
    void aBalanceThatDiffersFromTheLedgerIsReported() {
        Map<String, Long> ledger = Map.of(LedgerApi.EXT_FUNDING, -10_000L, "2000000001", 10_000L);
        Map<String, Long> balances = Map.of("2000000001", 9_000L);          // 1,000 kobo missing

        ReconciliationReport r = ReconciliationRules.check(NOW, 2, 1, NONE, ledger, balances);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("****0001") && p.contains("ledger says"));
        assertThat(r.problems()).anyMatch(p -> p.contains("does not add up"));
    }

    @Test
    void anUnbalancedJournalIsReported() {
        Map<String, Long> ledger = Map.of(LedgerApi.EXT_FUNDING, -10_000L, "2000000001", 10_000L);
        Map<String, Long> balances = Map.of("2000000001", 10_000L);
        List<Object[]> bad = List.<Object[]>of(new Object[]{"trf_x:INTERNAL", 500L, 400L});

        ReconciliationReport r = ReconciliationRules.check(NOW, 2, 1, bad, ledger, balances);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("trf_x:INTERNAL") && p.contains("does not balance"));
    }

    @Test
    void ledgerLinesForAnUnknownAccountAreReported() {
        Map<String, Long> ledger = Map.of(LedgerApi.EXT_FUNDING, -10_000L, "2999999999", 10_000L);

        ReconciliationReport r = ReconciliationRules.check(NOW, 2, 1, NONE, ledger, Map.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("unknown account"));
    }

    @Test
    void releasingMoreThanWasReservedIsReported() {
        Map<String, Long> ledger = Map.of(
                LedgerApi.EXT_FUNDING, -10_000L, LedgerApi.EXT_PAID_OUT, 12_000L, LedgerApi.EXT_IN_FLIGHT, -2_000L,
                "2000000001", 0L);
        Map<String, Long> balances = Map.of("2000000001", 0L);

        ReconciliationReport r = ReconciliationRules.check(NOW, 4, 2, NONE, ledger, balances);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("released from in-flight"));
    }
}