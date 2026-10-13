package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.CurrencyTotals;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.academy.paybridge.ledger.api.JournalLine.Side.CREDIT;
import static com.academy.paybridge.ledger.api.JournalLine.Side.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step 2 against the real database: the currencies from V10, a two-currency journal, per-currency reconciliation.
 * No @Transactional on the class on purpose: these tests need real commits. The ledger is append-only, so the
 * journals written here stay in the test database; they only touch EXT: accounts and reconcile cleanly.
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiCurrencyLedgerTest {

    @Autowired
    LedgerApi ledger;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    CurrencyRegistry registry;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @Test
    void theRegistryIsLoadedFromTheCurrenciesTable() {
        assertThat(registry.usable()).hasSize(50);
        assertThat(registry.require("NGN").minorUnits()).isEqualTo(2);
        assertThat(registry.require("JPY").minorUnits()).isZero();
        assertThat(registry.require("KWD").minorUnits()).isEqualTo(3);
        assertThat(registry.isUsable("IRR")).isFalse();                        // sanctioned: never usable
        assertThat(registry.isUsable("KPW")).isFalse();
    }

    @Test
    void everyCountryPointsAtARealCurrency() {
        assertThat(jdbc.queryForObject("select currency_code from countries where code = 'NG'", String.class)).isEqualTo("NGN");
        assertThat(jdbc.queryForObject("select count(*) from countries where currency_code not in "
                + "(select code from currencies)", Integer.class)).isZero();
    }

    @Test
    void aTwoCurrencyJournalIsStoredAndReconciliationStaysClean() {
        String key = "CONV-TEST:" + UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s -> ledger.recordJournal(key, null, "CONVERSION", List.of(
                new JournalLine(LedgerApi.EXT_FUNDING, DEBIT, "USD", 1_000),
                new JournalLine("EXT:FX_POOL:USD", CREDIT, "USD", 1_000),
                new JournalLine("EXT:FX_POOL:EUR", DEBIT, "EUR", 500),
                new JournalLine(LedgerApi.EXT_IN_FLIGHT, CREDIT, "EUR", 500))));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select currency, count(*) as n from ledger_entries where journal_key = ? group by currency order by currency", key);
        assertThat(rows).extracting(r -> r.get("currency")).containsExactly("EUR", "USD");
        assertThat(rows).extracting(r -> ((Number) r.get("n")).intValue()).containsExactly(2, 2);

        ReconciliationReport report = reconciliation.run();
        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.currencies()).extracting(CurrencyTotals::currency).contains("EUR", "NGN", "USD");
    }

    @Test
    void anUnbalancedSecondCurrencySavesNothing() {
        String key = "CONV-TEST:" + UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s ->
                ledger.recordJournal(key, null, "CONVERSION", List.of(
                        new JournalLine(LedgerApi.EXT_FUNDING, DEBIT, "USD", 1_000),
                        new JournalLine("EXT:FX_POOL:USD", CREDIT, "USD", 1_000),
                        new JournalLine("EXT:FX_POOL:EUR", DEBIT, "EUR", 500),
                        new JournalLine(LedgerApi.EXT_IN_FLIGHT, CREDIT, "EUR", 400)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EUR");

        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where journal_key = ?", Integer.class, key)).isZero();
    }
}