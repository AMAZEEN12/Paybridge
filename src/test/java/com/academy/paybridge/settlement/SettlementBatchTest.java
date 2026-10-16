package com.academy.paybridge.settlement;

import com.academy.paybridge.remittance.api.PayoutDayApi;
import com.academy.paybridge.settlement.api.BatchView;
import com.academy.paybridge.settlement.api.RunSummary;
import com.academy.paybridge.settlement.service.SettlementBatchService;
import com.academy.paybridge.shared.api.DayItem;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/** Plan 002-004, step C2 (R-SET-3 to 7; acceptance 23). Payouts are faked so no real partner is called. */
@SpringBootTest
@ActiveProfiles("test")
class SettlementBatchTest {

    // A day far in the past, so no real data from other tests can fall inside it.
    private static final LocalDate DAY = LocalDate.of(2001, 3, 4);

    @Autowired SettlementBatchService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PayoutDayApi payouts;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM partner_statement_lines WHERE batch_id IN (SELECT id FROM settlement_batches WHERE period_date = ?)", java.sql.Date.valueOf(DAY));
        jdbc.update("DELETE FROM settlement_batch_lines WHERE batch_id IN (SELECT id FROM settlement_batches WHERE period_date = ?)", java.sql.Date.valueOf(DAY));
        jdbc.update("DELETE FROM settlement_batches WHERE period_date = ?", java.sql.Date.valueOf(DAY));
        jdbc.update("DELETE FROM settlement_runs WHERE period_date = ?", java.sql.Date.valueOf(DAY));
    }

    private void twoUsdPayoutsAndOneEur() {
        Instant from = SettlementBatchService.window(DAY)[0];
        Instant to = SettlementBatchService.window(DAY)[1];
        when(payouts.payoutsBetween(from, to)).thenReturn(List.of(
                new DayItem("PO-1", "PAYOUT", "USD", 10_000, "SUCCESSFUL"),
                new DayItem("PO-2", "PAYOUT", "USD", 2_500, "PENDING"),
                new DayItem("PO-3", "PAYOUT", "EUR", 7_000, "FAILED")));
    }

    @Test
    void oneBatchPerPartnerCurrencyDay_andRunningTwiceChangesNothing() {
        twoUsdPayoutsAndOneEur();

        RunSummary first = service.runManually(DAY);
        assertThat(first.batchesCreated()).isEqualTo(2);

        List<BatchView> batches = service.list(DAY);
        assertThat(batches).extracting(BatchView::currency).containsExactly("EUR", "USD");
        BatchView usd = batches.get(1);
        assertThat(usd.partner()).isEqualTo("FLUTTERWAVE");
        assertThat(usd.status()).isEqualTo("CLOSED");
        assertThat(usd.itemCount()).isEqualTo(2);
        assertThat(usd.ourTotalMinor()).isEqualTo(12_500);
        assertThat(usd.partnerTotalMinor()).isEqualTo(12_500);   // the fake statement mirrors our payouts
        assertThat(batches.get(0).ourTotalMinor()).isZero();      // a FAILED payout is counted but not totalled

        // The scheduled path (catch-up) and a manual run both leave the same rows.
        assertThat(service.runDay(DAY).batchesCreated()).isZero();
        assertThat(service.list(DAY)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_batch_lines l JOIN settlement_batches b ON b.id = l.batch_id WHERE b.period_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_statement_lines s JOIN settlement_batches b ON b.id = s.batch_id WHERE b.period_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY))).isEqualTo(3);
    }

    @Test
    void aManualRunForADayThatAlreadyRanIsRefused() {
        twoUsdPayoutsAndOneEur();
        service.runManually(DAY);
        assertThatThrownBy(() -> service.runManually(DAY)).hasMessageContaining("already been run");
        assertThatThrownBy(() -> service.runManually(LocalDate.now(ZoneId.of("UTC")))).hasMessageContaining("finished");
    }

    @Test
    void aClosedBatchCannotBeChanged() {
        twoUsdPayoutsAndOneEur();
        service.runManually(DAY);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_batches SET our_total_minor = 1 WHERE period_date = ?",
                java.sql.Date.valueOf(DAY))).hasMessageContaining("closed");
    }

    @Test
    void aTransferAt0030LagosFallsInThePreviousUtcDay() {
        Instant lagos0030 = OffsetDateTime.parse("2026-10-16T00:30:00+01:00").toInstant();   // 23:30 UTC on the 15th
        Instant[] day15 = SettlementBatchService.window(LocalDate.of(2026, 10, 15));
        Instant[] day16 = SettlementBatchService.window(LocalDate.of(2026, 10, 16));
        assertThat(lagos0030).isAfterOrEqualTo(day15[0]).isBefore(day15[1]);
        assertThat(lagos0030).isBefore(day16[0]);
        assertThat(day15[1]).isEqualTo(day16[0]);   // the days touch, with no gap and no overlap
    }
}