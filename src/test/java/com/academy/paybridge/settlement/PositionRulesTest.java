package com.academy.paybridge.settlement;

import com.academy.paybridge.ledger.api.AccountPosition;
import com.academy.paybridge.ledger.api.JournalDraft;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PayoutJournals;
import com.academy.paybridge.settlement.api.PositionReport;
import com.academy.paybridge.settlement.service.PositionRules;
import com.academy.paybridge.settlement.service.SettlementProperties.Threshold;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 002-004, step C1: a position is credits minus debits (R-SET-1, R-SET-2). Pure rules, no database. */
class PositionRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String REF = "rmt_0123456789abcdef0123456789abcdef";
    private static final String ACCT = "2000000001";

    // A payout of $50.00: fee $0.50, net $49.50, the bank receives 74,250.00 NGN (kobo).
    private static final long X = 5_000, F = 50, N = 4_950, Y = 7_425_000;

    /** Folds journals into ledger rows: credits minus debits per account and currency. */
    private static List<AccountPosition> rows(JournalDraft... drafts) {
        Map<String, Long> net = new LinkedHashMap<>();
        for (JournalDraft d : drafts) {
            for (JournalLine l : d.lines()) {
                net.merge(l.account() + "|" + l.currency(),
                        l.side() == JournalLine.Side.CREDIT ? l.amountMinor() : -l.amountMinor(), Long::sum);
            }
        }
        List<AccountPosition> rows = new ArrayList<>();
        net.forEach((k, v) -> {
            String[] p = k.split("\\|");
            rows.add(new AccountPosition(p[0], p[1], v));
        });
        return rows;
    }

    private static PositionReport report(List<AccountPosition> rows, List<Threshold> thresholds) {
        return PositionRules.build(NOW, rows, thresholds, (minor, currency) -> minor + " " + currency);
    }

    private static PositionReport.Position position(PositionReport r, String currency, String account) {
        return r.currencies().stream().filter(c -> c.currency().equals(currency)).findFirst().orElseThrow()
                .positions().stream().filter(p -> p.account().equals(account)).findFirst().orElseThrow();
    }

    private static long net(PositionReport r, String currency, String account) {
        return position(r, currency, account).netMinor();
    }

    @Test
    void anEmptyLedgerShowsNairaWithEveryAccountAtZeroAndNothingFlagged() {
        PositionReport r = report(List.of(), List.of());

        assertThat(r.currencies()).extracting(c -> c.currency()).containsExactly("NGN");
        assertThat(r.currencies().get(0).positions()).hasSize(11)
                .allSatisfy(p -> assertThat(p.netMinor()).isZero());
        assertThat(r.currencies().get(0).positions()).extracting(p -> p.account())
                .contains("EXT:IN_FLIGHT", "EXT:PAID_OUT", "EXT:TAX_PAYABLE:NGN",
                        "9000000001:NGN", "9000000002:NGN", "9000000003:NGN");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void aPaidPayoutWithConversionLeavesTheSignsTheLedgerHas() {
        PositionReport r = report(rows(
                PayoutJournals.reserve(REF, ACCT, "USD", X),
                PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y)), List.of());

        assertThat(net(r, "USD", "EXT:FX_POOL:USD")).isEqualTo(N);            // the pool took the net
        assertThat(net(r, "USD", "EXT:PAYOUT_FEE:USD")).isEqualTo(F);
        assertThat(net(r, "USD", "EXT:IN_FLIGHT:USD")).isZero();              // reserved then released
        assertThat(net(r, "NGN", "EXT:FX_POOL:NGN")).isEqualTo(-Y);           // the pool gave out naira
        assertThat(net(r, "NGN", "EXT:PAID_OUT")).isEqualTo(Y);               // NGN keeps the legacy name
    }

    @Test
    void aReservedPayoutIsInFlightAndARefundedOneIsNot() {
        PositionReport reserved = report(rows(PayoutJournals.reserve(REF, ACCT, "USD", X)), List.of());
        PositionReport refunded = report(rows(PayoutJournals.reserve(REF, ACCT, "USD", X),
                PayoutJournals.refund(REF, ACCT, "USD", X)), List.of());

        assertThat(net(reserved, "USD", "EXT:IN_FLIGHT:USD")).isEqualTo(X);
        assertThat(net(refunded, "USD", "EXT:IN_FLIGHT:USD")).isZero();
    }

    @Test
    void aWorseRateDifferenceDebitsRateDiffAndCreditsThePool() {
        PositionReport r = report(rows(
                PayoutJournals.reserve(REF, ACCT, "USD", X),
                PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y),
                PayoutJournals.rateDifference(REF, "USD", 99)), List.of());

        assertThat(net(r, "USD", "EXT:RATE_DIFF:USD")).isEqualTo(-99);        // PayBridge carried 99
        assertThat(net(r, "USD", "EXT:FX_POOL:USD")).isEqualTo(N + 99);
    }

    @Test
    void fundingAndCustomerWalletsAreNotPositions() {
        List<AccountPosition> rows = List.of(
                new AccountPosition(LedgerApi.EXT_FUNDING, "EUR", -10_000),
                new AccountPosition(ACCT + ":EUR", "EUR", 10_000));

        PositionReport r = report(rows, List.of());

        assertThat(r.currencies()).extracting(c -> c.currency()).containsExactly("NGN");   // EUR has no tracked account
    }

    @Test
    void theSystemWalletsShowTheirNairaBalance() {
        PositionReport r = report(List.of(new AccountPosition("9000000001:NGN", "NGN", 2_500)), List.of());

        assertThat(net(r, "NGN", "9000000001:NGN")).isEqualTo(2_500);
    }

    @Test
    void aPositionBelowItsMinimumIsFlaggedAndWarnedAndOthersAreNot() {
        List<AccountPosition> rows = rows(PayoutJournals.reserve(REF, ACCT, "USD", X),
                PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y));
        List<Threshold> thresholds = List.of(new Threshold("EXT:FX_POOL:NGN", 0), new Threshold("EXT:FX_POOL:USD", 1_000));

        PositionReport r = report(rows, thresholds);

        assertThat(position(r, "NGN", "EXT:FX_POOL:NGN").belowThreshold()).isTrue();
        assertThat(position(r, "USD", "EXT:FX_POOL:USD").belowThreshold()).isFalse();      // 4,950 is above 1,000
        assertThat(position(r, "USD", "EXT:FX_POOL:USD").thresholdMinor()).isEqualTo(1_000L);
        assertThat(position(r, "USD", "EXT:PAYOUT_FEE:USD").thresholdMinor()).isNull();
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0)).contains("EXT:FX_POOL:NGN").contains("below its minimum");
    }
}