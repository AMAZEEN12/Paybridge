package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.CurrencyTotals;
import com.academy.paybridge.ledger.api.JournalDraft;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PayoutJournals;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationRules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step B1: a payout's phases, folded into ledger rows, must reconcile in every currency. This is the test that
 * proves reconciliation knows the new accounts (R-LED2-4).
 */
class PayoutReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String REF = "trf_0123456789abcdef0123456789abcdef";
    private static final String ACCT = "2000000001";
    private static final String WALLET = ACCT + ":USD";

    // $100.00 funded; a payout of X = $50.00 (fee $0.50, net $49.50); the recipient gets 74,250.00 NGN (kobo)
    private static final long FUNDED = 10_000, X = 5_000, F = 50, N = 4_950, Y = 7_425_000;

    /** Folds journals into the rows reconciliation reads: [account, currency, credits minus debits]. */
    private static final class Book {
        private final Map<String, Long> net = new HashMap<>();

        Book post(String account, String currency, long creditMinusDebit) {
            net.merge(account + "|" + currency, creditMinusDebit, Long::sum);
            return this;
        }

        Book funded() {
            post(LedgerApi.EXT_FUNDING, "USD", -FUNDED);
            post(WALLET, "USD", FUNDED);
            return this;
        }

        Book apply(JournalDraft d) {
            for (JournalLine l : d.lines()) {
                post(l.account(), l.currency(), l.side() == JournalLine.Side.CREDIT ? l.amountMinor() : -l.amountMinor());
            }
            return this;
        }

        List<Object[]> rows() {
            List<Object[]> rows = new ArrayList<>();
            net.forEach((k, v) -> {
                String[] p = k.split("\\|");
                rows.add(new Object[]{p[0], p[1], v});
            });
            return rows;
        }
    }

    private static ReconciliationReport run(Book book, long walletBalance) {
        return ReconciliationRules.checkByCurrency(NOW, 0, 0, List.of(), book.rows(), Map.of(WALLET, walletBalance), null);
    }

    private static CurrencyTotals totals(ReconciliationReport r, String currency) {
        return r.currencies().stream().filter(t -> t.currency().equals(currency)).findFirst().orElseThrow();
    }

    @Test
    void aReservedPayoutIsInFlightAndReconciles() {
        Book book = new Book().funded().apply(PayoutJournals.reserve(REF, ACCT, "USD", X));

        ReconciliationReport r = run(book, FUNDED - X);

        assertThat(r.problems()).isEmpty();
        assertThat(totals(r, "USD").inFlightMinor()).isEqualTo(X);
        assertThat(totals(r, "USD").heldMinor()).isEqualTo(FUNDED - X);
    }

    @Test
    void aSuccessfulPayoutWithConversionReconcilesInBothCurrencies() {
        Book book = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y));

        ReconciliationReport r = run(book, FUNDED - X);

        assertThat(r.problems()).isEmpty();
        assertThat(totals(r, "USD").inFlightMinor()).isZero();
        assertThat(totals(r, "NGN").paidOutMinor()).isEqualTo(Y);
    }

    @Test
    void aSuccessfulPayoutWithoutConversionPaysOutInTheSameCurrency() {
        Book book = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.paidNoConversion(REF, "USD", X, N, F));

        ReconciliationReport r = run(book, FUNDED - X);

        assertThat(r.problems()).isEmpty();
        assertThat(totals(r, "USD").paidOutMinor()).isEqualTo(N);       // read from EXT:PAID_OUT:USD
    }

    @Test
    void aFailedPayoutPutsTheMoneyBack() {
        Book book = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.refund(REF, ACCT, "USD", X));

        ReconciliationReport r = run(book, FUNDED);

        assertThat(r.problems()).isEmpty();
        assertThat(totals(r, "USD").inFlightMinor()).isZero();
    }

    @Test
    void aRateDifferenceInEitherDirectionStillReconciles() {
        Book worse = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y))
                .apply(PayoutJournals.rateDifference(REF, "USD", 30));
        Book better = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y))
                .apply(PayoutJournals.rateDifference(REF, "USD", -30));

        assertThat(run(worse, FUNDED - X).problems()).isEmpty();
        assertThat(run(better, FUNDED - X).problems()).isEmpty();
    }

    @Test
    void theRetryPhasesReconcileToo() {
        Book paid = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.conversionDone(REF, "USD", "NGN", X, N, F, Y))
                .apply(PayoutJournals.paidAfterConversion(REF, "NGN", Y));
        Book refunded = new Book().funded()
                .apply(PayoutJournals.reserve(REF, ACCT, "USD", X))
                .apply(PayoutJournals.conversionDone(REF, "USD", "NGN", X, N, F, Y))
                .apply(PayoutJournals.refundAfterConversion(REF, ACCT, "USD", "NGN", X, N, F, Y));

        assertThat(run(paid, FUNDED - X).problems()).isEmpty();
        ReconciliationReport r = run(refunded, FUNDED);
        assertThat(r.problems()).isEmpty();
        assertThat(totals(r, "USD").inFlightMinor()).isZero();
        assertThat(totals(r, "NGN").inFlightMinor()).isZero();
    }

    @Test
    void anUnknownOutsideAccountIsReported() {
        Book book = new Book().funded().post("EXT:MYSTERY:USD", "USD", 0);

        ReconciliationReport r = run(book, FUNDED);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("unknown outside account EXT:MYSTERY:USD"));
    }

    @Test
    void everyKnownOutsideAccountIsAccepted() {
        Book book = new Book().funded()
                .post("EXT:PAYOUT_FEE:USD", "USD", 0).post("EXT:RATE_DIFF:USD", "USD", 0)
                .post("EXT:REVENUE:USD", "USD", 0).post("EXT:TAX_PAYABLE:NGN", "NGN", 0)
                .post("EXT:IN_FLIGHT:USD", "USD", 0).post("EXT:PAID_OUT:USD", "USD", 0)
                .post("EXT:FX_FEE:USD", "USD", 0).post("EXT:FX_POOL:USD", "USD", 0);

        assertThat(run(book, FUNDED).problems()).isEmpty();
    }

    @Test
    void moneyThatLeavesWithoutALedgerPartnerIsStillCaught() {
        // The wallet was debited but no journal was posted for it: the books no longer add up.
        Book book = new Book().funded();

        ReconciliationReport r = run(book, FUNDED - X);

        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("[USD]"));
    }
}