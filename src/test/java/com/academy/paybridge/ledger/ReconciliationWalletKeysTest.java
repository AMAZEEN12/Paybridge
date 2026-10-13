package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationWalletKeysTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Object[]> NONE = List.of();

    @Test
    void walletKeysReconcileLikePlainKeys() {
        Map<String, Long> ledger = Map.of(
                LedgerApi.EXT_FUNDING, -100_000L,
                LedgerApi.EXT_PAID_OUT, 20_000L,
                LedgerApi.EXT_IN_FLIGHT, 5_000L,
                "2000000001:NGN", 74_000L,
                "9000000001:NGN", 1_000L);
        Map<String, Long> balances = Map.of("2000000001:NGN", 74_000L, "9000000001:NGN", 1_000L);

        ReconciliationReport r = ReconciliationRules.check(NOW, 8, 4, NONE, ledger, balances);

        assertThat(r.ok()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.heldKobo()).isEqualTo(75_000L);
    }

    @Test
    void messagesShowTheAccountNumberWithoutTheCurrencySuffix() {
        Map<String, Long> ledger = Map.of(
                LedgerApi.EXT_FUNDING, -10_000L, "2000000001:NGN", 10_000L, "2000000009:NGN", 0L);
        Map<String, Long> balances = Map.of("2000000001:NGN", 9_000L);

        ReconciliationReport r = ReconciliationRules.check(NOW, 2, 1, NONE, ledger, balances);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("****0001").doesNotContain(":NGN"));
        assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("****0009").doesNotContain(":NGN"));
    }

    @Test
    void walletKeyHelpers() {
        assertThat(LedgerApi.walletKey("2000000001", "NGN")).isEqualTo("2000000001:NGN");
        assertThat(LedgerApi.walletKey(LedgerApi.EXT_FUNDING, "NGN")).isEqualTo("EXT:FUNDING");
        assertThat(LedgerApi.accountNumberOf("2000000001:NGN")).isEqualTo("2000000001");
        assertThat(LedgerApi.accountNumberOf("EXT:IN_FLIGHT")).isEqualTo("EXT:IN_FLIGHT");
        assertThat(LedgerApi.accountNumberOf("2000000001")).isEqualTo("2000000001");
    }
}