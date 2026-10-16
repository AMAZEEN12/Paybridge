package com.academy.paybridge.settlement;

import com.academy.paybridge.remittance.api.PayoutDayApi;
import com.academy.paybridge.settlement.api.ExceptionView;
import com.academy.paybridge.settlement.service.FakeStatementProvider;
import com.academy.paybridge.settlement.service.SettlementBatchService;
import com.academy.paybridge.settlement.service.SettlementMatchingService;
import com.academy.paybridge.settlement.service.StatementProvider;
import com.academy.paybridge.shared.api.DayItem;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/** Plan 002-004, step C3 (R-SET-8 to 10; acceptance 24 to 26). The partner statement is the fake; no real partner is called. */
@SpringBootTest
@ActiveProfiles("test")
class SettlementMatchingTest {

    // A day far in the past, so no real data from other tests falls inside it and the statement lag has long passed.
    private static final LocalDate DAY = LocalDate.of(2001, 3, 6);
    private static final Instant FROM = SettlementBatchService.window(DAY)[0];
    private static final Instant TO = SettlementBatchService.window(DAY)[1];

    @Autowired SettlementBatchService service;
    @Autowired SettlementMatchingService matching;
    @Autowired FakeStatementProvider fake;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PayoutDayApi payouts;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @BeforeEach
    void freshFake() {
        fake.reset();
    }

    @AfterEach
    void clean() {
        fake.reset();
        java.sql.Date day = java.sql.Date.valueOf(DAY);
        // Exceptions are never deleted by the application; the test switches the guard off just to tidy up.
        jdbc.execute("ALTER TABLE settlement_exceptions DISABLE TRIGGER trg_settlement_exception_guard");
        try {
            jdbc.update("DELETE FROM settlement_exceptions WHERE batch_id IN (SELECT id FROM settlement_batches WHERE period_date = ?)", day);
        } finally {
            jdbc.execute("ALTER TABLE settlement_exceptions ENABLE TRIGGER trg_settlement_exception_guard");
        }
        jdbc.update("DELETE FROM partner_statement_lines WHERE batch_id IN (SELECT id FROM settlement_batches WHERE period_date = ?)", day);
        jdbc.update("DELETE FROM settlement_batch_lines WHERE batch_id IN (SELECT id FROM settlement_batches WHERE period_date = ?)", day);
        jdbc.update("DELETE FROM settlement_batches WHERE period_date = ?", day);
        jdbc.update("DELETE FROM settlement_runs WHERE period_date = ?", day);
    }

    private void ourPayouts(DayItem... items) {
        when(payouts.payoutsBetween(FROM, TO)).thenReturn(List.of(items));
    }

    private static StatementProvider.Line line(String ref, String currency, long amount, String status) {
        return new StatementProvider.Line(ref, currency, amount, status, FROM.plusSeconds(60));
    }

    private List<String> exceptions() {
        return jdbc.queryForList("SELECT e.reference || '=' || e.type FROM settlement_exceptions e "
                        + "JOIN settlement_batches b ON b.id = e.batch_id WHERE b.period_date = ? ORDER BY e.reference",
                String.class, java.sql.Date.valueOf(DAY));
    }

    private String batchStatus(String currency) {
        return jdbc.queryForObject("SELECT status FROM settlement_batches WHERE period_date = ? AND partner = 'FLUTTERWAVE' AND currency = ?",
                String.class, java.sql.Date.valueOf(DAY), currency);
    }

    private long exceptionId(String reference) {
        return jdbc.queryForObject("SELECT e.id FROM settlement_exceptions e JOIN settlement_batches b ON b.id = e.batch_id "
                + "WHERE b.period_date = ? AND e.reference = ?", Long.class, java.sql.Date.valueOf(DAY), reference);
    }

    private void fiveKindsOfTrouble() {
        ourPayouts(
                new DayItem("PO-OK", "PAYOUT", "USD", 10_000, "SUCCESSFUL"),
                new DayItem("PO-MISSING", "PAYOUT", "USD", 5_000, "SUCCESSFUL"),
                new DayItem("PO-AMT", "PAYOUT", "USD", 3_000, "SUCCESSFUL"),
                new DayItem("PO-STATUS", "PAYOUT", "USD", 4_000, "SUCCESSFUL"),
                new DayItem("PO-PEND", "PAYOUT", "USD", 2_500, "PENDING"));
        fake.hide("PO-MISSING");
        fake.override(line("PO-AMT", "USD", 2_900, "SUCCESS"));
        fake.override(line("PO-STATUS", "USD", 4_000, "FAILED"));
        fake.addExtra(line("PO-GHOST", "USD", 1_234, "SUCCESS"));
    }

    @Test
    void missingMismatchedAndPendingEachBecomeTheRightException_andAMatchDoesNot() {   // acceptance 24
        fiveKindsOfTrouble();
        service.runManually(DAY);

        matching.matchDue();

        assertThat(exceptions()).containsExactly(
                "PO-AMT=AMOUNT_DIFFERS",
                "PO-GHOST=MISSING_ON_OUR_SIDE",
                "PO-MISSING=MISSING_AT_PARTNER",
                "PO-PEND=STILL_PENDING",
                "PO-STATUS=STATUS_DIFFERS");
        assertThat(batchStatus("USD")).isEqualTo("EXCEPTION");
        assertThat(jdbc.queryForObject("SELECT match_result FROM settlement_batch_lines WHERE reference = 'PO-OK'", String.class))
                .isEqualTo("MATCHED");
    }

    @Test
    void runningMatchingAgainChangesNothing() {
        fiveKindsOfTrouble();
        service.runManually(DAY);
        matching.matchDue();

        matching.matchDue();
        matching.matchBatch(jdbc.queryForObject("SELECT id FROM settlement_batches WHERE period_date = ? AND currency = 'USD'",
                Long.class, java.sql.Date.valueOf(DAY)));

        assertThat(exceptions()).hasSize(5);
    }

    @Test
    void aPendingPayoutIsReportedAndNothingIsRefunded() {   // acceptance 25
        fiveKindsOfTrouble();
        service.runManually(DAY);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);

        matching.matchDue();

        assertThat(jdbc.queryForObject("SELECT match_result FROM settlement_batch_lines WHERE reference = 'PO-PEND'", String.class))
                .isEqualTo("STILL_PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(before);
    }

    @Test
    void anOperatorResolvesAnExceptionWithANote_itIsStoredAndAudited_andNothingIsEdited() {   // acceptance 26
        fiveKindsOfTrouble();
        service.runManually(DAY);
        matching.matchDue();
        long id = exceptionId("PO-MISSING");

        ExceptionView done = matching.resolve(id, "operator:42", "Partner confirmed it landed on the 7th.");

        assertThat(done.resolvedAt()).isNotNull();
        assertThat(done.resolvedBy()).isEqualTo("operator:42");
        assertThat(done.resolutionNote()).isEqualTo("Partner confirmed it landed on the 7th.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SETTLEMENT_EXCEPTION_RESOLVED' AND reference = ?",
                Integer.class, "settlement-exception:" + id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_log WHERE action = 'SETTLEMENT_EXCEPTION_RESOLVED' AND reference = ?",
                String.class, "settlement-exception:" + id)).contains("PO-MISSING").contains("Partner confirmed");

        // Nothing is edited or deleted, and an exception is resolved once.
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_exceptions SET type = 'AMOUNT_DIFFERS' WHERE id = ?", id))
                .hasMessageContaining("can only be resolved");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlement_exceptions WHERE id = ?", id))
                .hasMessageContaining("never deleted");
        assertThatThrownBy(() -> matching.resolve(id, "operator:42", "again")).isInstanceOf(ApiException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    void aNoteIsRequired() {
        fiveKindsOfTrouble();
        service.runManually(DAY);
        matching.matchDue();
        long id = exceptionId("PO-AMT");

        assertThatThrownBy(() -> matching.resolve(id, "operator:42", "   ")).isInstanceOf(ApiException.class)
                .hasMessageContaining("note is required");
        assertThatThrownBy(() -> matching.resolve(id, "operator:42", null)).isInstanceOf(ApiException.class);
        assertThat(matching.list(false)).extracting(ExceptionView::reference).contains("PO-AMT");
    }

    @Test
    void theBatchIsSettledOnlyWhenEveryExceptionIsResolved() {
        fiveKindsOfTrouble();
        service.runManually(DAY);
        matching.matchDue();

        List<String> refs = List.of("PO-AMT", "PO-GHOST", "PO-MISSING", "PO-PEND", "PO-STATUS");
        for (int i = 0; i < refs.size(); i++) {
            assertThat(batchStatus("USD")).isEqualTo("EXCEPTION");
            matching.resolve(exceptionId(refs.get(i)), "operator:42", "Checked with the partner.");
        }

        assertThat(batchStatus("USD")).isEqualTo("SETTLED");
        assertThat(matching.list(false)).isEmpty();
        assertThat(matching.list(true)).hasSize(5);
    }

    @Test
    void aCleanDayIsSettledWithNoExceptions() {
        ourPayouts(
                new DayItem("PO-1", "PAYOUT", "USD", 10_000, "SUCCESSFUL"),
                new DayItem("PO-F", "PAYOUT", "USD", 500, "FAILED"));
        service.runManually(DAY);

        matching.matchDue();

        assertThat(exceptions()).isEmpty();
        assertThat(batchStatus("USD")).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_at IS NOT NULL FROM settlement_batches WHERE period_date = ? AND currency = 'USD'",
                Boolean.class, java.sql.Date.valueOf(DAY))).isTrue();
    }

    @Test
    void aPartnerLineInACurrencyWeHadNothingInStillGetsABatchAndAnException() {
        ourPayouts(new DayItem("PO-1", "PAYOUT", "USD", 10_000, "SUCCESSFUL"));
        fake.addExtra(line("PO-EUR-GHOST", "EUR", 7_000, "SUCCESS"));

        service.runManually(DAY);
        matching.matchDue();

        assertThat(exceptions()).containsExactly("PO-EUR-GHOST=MISSING_ON_OUR_SIDE");
        assertThat(batchStatus("EUR")).isEqualTo("EXCEPTION");
        assertThat(batchStatus("USD")).isEqualTo("SETTLED");
    }
}