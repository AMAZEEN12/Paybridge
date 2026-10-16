package com.academy.paybridge.ledger;

import com.academy.paybridge.ledger.api.JournalDraft;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.JournalLine.Side;
import com.academy.paybridge.ledger.api.PayoutJournals;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure tests (step B1): every payout journal balances in every currency on its own, and fits the ledger table. */
class PayoutJournalsTest {

    private static final String REF = "trf_0123456789abcdef0123456789abcdef";
    private static final String ACCT = "2000000001";
    private static final Pattern SUFFIX = Pattern.compile(":([A-Z]{3})$");

    // S = USD, T = NGN: X = $50.00, fee $0.50, net $49.50, the recipient gets N74,250.00 (kobo)
    private static final long X = 5_000, F = 50, N = 4_950, Y = 7_425_000;

    /** The rules the ledger enforces on every journal, plus the column limits of ledger_entries. */
    private static void assertLedgerReady(JournalDraft d) {
        Map<String, long[]> byCurrency = new TreeMap<>();
        Set<String> lineIds = new HashSet<>();
        for (JournalLine l : d.lines()) {
            assertThat(l.amountMinor()).as("positive line " + l).isPositive();
            assertThat(l.account().length()).as("account fits 20 characters: " + l.account()).isLessThanOrEqualTo(20);
            Matcher m = SUFFIX.matcher(l.account());
            if (m.find()) {
                assertThat(m.group(1)).as("key suffix matches the line currency: " + l.account()).isEqualTo(l.currency());
            }
            // the ledger table allows one line per account per side in a journal (uq_ledger_line)
            assertThat(lineIds.add(l.account() + "|" + l.side())).as("no repeated account and side: " + l).isTrue();
            long[] t = byCurrency.computeIfAbsent(l.currency(), c -> new long[2]);
            t[l.side() == Side.DEBIT ? 0 : 1] += l.amountMinor();
        }
        byCurrency.forEach((currency, t) ->
                assertThat(t[0]).as(d.key() + " balances in " + currency).isEqualTo(t[1]));
        assertThat(d.key().length()).isLessThanOrEqualTo(80);
        assertThat(d.entryType().length()).isLessThanOrEqualTo(20);
    }

    private static long total(JournalDraft d, String account, Side side) {
        return d.lines().stream().filter(l -> l.account().equals(account) && l.side() == side)
                .mapToLong(JournalLine::amountMinor).sum();
    }

    private static List<JournalDraft> everyPhase() {
        return List.of(
                PayoutJournals.reserve(REF, ACCT, "USD", X),
                PayoutJournals.paidNoConversion(REF, "USD", X, N, F),
                PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y),
                PayoutJournals.refund(REF, ACCT, "USD", X),
                PayoutJournals.rateDifference(REF, "USD", 30),
                PayoutJournals.rateDifference(REF, "USD", -30),
                PayoutJournals.conversionDone(REF, "USD", "NGN", X, N, F, Y),
                PayoutJournals.paidAfterConversion(REF, "NGN", Y),
                PayoutJournals.refundAfterConversion(REF, ACCT, "USD", "NGN", X, N, F, Y));
    }

    @Test
    void everyPhaseBalancesInEveryCurrencyAndFitsTheLedgerTable() {
        everyPhase().forEach(PayoutJournalsTest::assertLedgerReady);
    }

    @Test
    void theSameJournalsBalanceWhenTheSourceIsNairaToo() {
        assertLedgerReady(PayoutJournals.reserve(REF, ACCT, "NGN", 5_000_000));
        assertLedgerReady(PayoutJournals.paidWithConversion(REF, "NGN", "USD", 5_000_000, 4_950_000, 50_000, 3_300));
        assertLedgerReady(PayoutJournals.refund(REF, ACCT, "NGN", 5_000_000));
    }

    @Test
    void keysFollowThePhaseNames() {
        assertThat(PayoutJournals.reserve(REF, ACCT, "USD", X).key()).isEqualTo("PAYOUT:" + REF + ":J1");
        assertThat(PayoutJournals.paidNoConversion(REF, "USD", X, N, F).key()).endsWith(":J3a");
        assertThat(PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y).key()).endsWith(":J3b");
        assertThat(PayoutJournals.refund(REF, ACCT, "USD", X).key()).endsWith(":J4");
        assertThat(PayoutJournals.rateDifference(REF, "USD", 1).key()).endsWith(":J6");
        assertThat(PayoutJournals.conversionDone(REF, "USD", "NGN", X, N, F, Y).key()).endsWith(":J2");
        assertThat(PayoutJournals.paidAfterConversion(REF, "NGN", Y).key()).endsWith(":J3c");
        assertThat(PayoutJournals.refundAfterConversion(REF, ACCT, "USD", "NGN", X, N, F, Y).key()).endsWith(":J5");
    }

    @Test
    void reserveMovesTheCustomersMoneyIntoFlight() {
        JournalDraft j1 = PayoutJournals.reserve(REF, ACCT, "USD", X);

        assertThat(total(j1, ACCT + ":USD", Side.DEBIT)).isEqualTo(X);
        assertThat(total(j1, "EXT:IN_FLIGHT:USD", Side.CREDIT)).isEqualTo(X);
        assertThat(j1.entryType()).isEqualTo("PAYOUT");
        assertThat(j1.reference()).isEqualTo(REF);
    }

    @Test
    void successWithConversionSplitsTheAmountAcrossBothCurrencies() {
        JournalDraft j3b = PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, Y);

        assertThat(total(j3b, "EXT:IN_FLIGHT:USD", Side.DEBIT)).isEqualTo(X);
        assertThat(total(j3b, "EXT:FX_POOL:USD", Side.CREDIT)).isEqualTo(N);
        assertThat(total(j3b, "EXT:PAYOUT_FEE:USD", Side.CREDIT)).isEqualTo(F);
        assertThat(total(j3b, "EXT:FX_POOL:NGN", Side.DEBIT)).isEqualTo(Y);
        assertThat(total(j3b, "EXT:PAID_OUT", Side.CREDIT)).isEqualTo(Y);        // NGN keeps its legacy name (S7)
    }

    @Test
    void nonNairaDestinationsUseTheSuffixedNames() {
        JournalDraft j3a = PayoutJournals.paidNoConversion(REF, "USD", X, N, F);

        assertThat(total(j3a, "EXT:PAID_OUT:USD", Side.CREDIT)).isEqualTo(N);
        assertThat(total(j3a, "EXT:PAYOUT_FEE:USD", Side.CREDIT)).isEqualTo(F);
    }

    @Test
    void aZeroFeeLeavesTheFeeLineOut() {
        JournalDraft j3a = PayoutJournals.paidNoConversion(REF, "USD", X, X, 0);

        assertThat(j3a.lines()).noneMatch(l -> l.account().startsWith("EXT:PAYOUT_FEE"));
        assertLedgerReady(j3a);
    }

    @Test
    void aRateDifferenceWorseThanQuotedDebitsRateDiffAndBetterSwapsTheSides() {
        JournalDraft worse = PayoutJournals.rateDifference(REF, "USD", 30);
        JournalDraft better = PayoutJournals.rateDifference(REF, "USD", -30);

        assertThat(total(worse, "EXT:RATE_DIFF:USD", Side.DEBIT)).isEqualTo(30);
        assertThat(total(worse, "EXT:FX_POOL:USD", Side.CREDIT)).isEqualTo(30);
        assertThat(total(better, "EXT:RATE_DIFF:USD", Side.CREDIT)).isEqualTo(30);
        assertThat(total(better, "EXT:FX_POOL:USD", Side.DEBIT)).isEqualTo(30);
    }

    @Test
    void theRefundAfterAConversionUndoesBothCurrencies() {
        JournalDraft j5 = PayoutJournals.refundAfterConversion(REF, ACCT, "USD", "NGN", X, N, F, Y);

        assertThat(total(j5, ACCT + ":USD", Side.CREDIT)).isEqualTo(X);
        assertThat(total(j5, "EXT:FX_POOL:USD", Side.DEBIT)).isEqualTo(N);
        assertThat(total(j5, "EXT:PAYOUT_FEE:USD", Side.DEBIT)).isEqualTo(F);
        assertThat(total(j5, "EXT:IN_FLIGHT", Side.DEBIT)).isEqualTo(Y);
        assertThat(total(j5, "EXT:FX_POOL:NGN", Side.CREDIT)).isEqualTo(Y);
    }

    @Test
    void badNumbersAreRefusedBeforeAnythingIsPosted() {
        assertThatThrownBy(() -> PayoutJournals.paidNoConversion(REF, "USD", X, N, F + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("net plus fee");
        assertThatThrownBy(() -> PayoutJournals.reserve(REF, ACCT, "USD", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutJournals.paidWithConversion(REF, "USD", "USD", X, N, F, Y))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two different currencies");
        assertThatThrownBy(() -> PayoutJournals.paidWithConversion(REF, "USD", "NGN", X, N, F, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutJournals.rateDifference(REF, "USD", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutJournals.paidNoConversion(REF, "USD", X, X + 10, -10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSixtyCharacterReferenceStillFitsTheJournalKeyColumn() {
        String longRef = "r".repeat(60);

        assertThat(PayoutJournals.refundAfterConversion(longRef, ACCT, "USD", "NGN", X, N, F, Y).key().length())
                .isLessThanOrEqualTo(80);
    }
}