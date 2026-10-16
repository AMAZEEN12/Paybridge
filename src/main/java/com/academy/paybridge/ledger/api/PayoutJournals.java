package com.academy.paybridge.ledger.api;

import com.academy.paybridge.ledger.api.JournalLine.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * The payout journals of spec 7.2, as pure builders: numbers in, lines out. No database. Each journal balances in
 * every currency on its own, and each is posted once under its own key {@code PAYOUT:<reference>:<phase>}.
 *
 * Notation: sender account A, source currency S, destination currency T, X debited from the customer, fee F,
 * net N = X - F, amount paid out Y (in T). Zero-value lines (a zero fee) are left out.
 *
 * J2, J3c and J5 exist only for the retry switch (S16, step B5); nothing calls them while retries are off.
 */
public final class PayoutJournals {

    private static final String TYPE = "PAYOUT";

    private PayoutJournals() {
    }

    public static String key(String reference, String phase) {
        return "PAYOUT:" + reference + ":" + phase;
    }

    /** J1, on confirm: the customer is debited and the money waits in flight. */
    public static JournalDraft reserve(String reference, String senderAccount, String s, long x) {
        positive(x, "amount");
        return draft(reference, "J1", new Lines()
                .add(LedgerApi.walletKey(senderAccount, s), Side.DEBIT, s, x)
                .add(LedgerAccounts.inFlight(s), Side.CREDIT, s, x));
    }

    /** J3a, payout paid and no conversion (S equals T): X leaves flight, N is paid out, F is the fee. */
    public static JournalDraft paidNoConversion(String reference, String s, long x, long n, long f) {
        split(x, n, f);
        return draft(reference, "J3a", new Lines()
                .add(LedgerAccounts.inFlight(s), Side.DEBIT, s, x)
                .add(LedgerAccounts.paidOut(s), Side.CREDIT, s, n)
                .addIfPositive(LedgerAccounts.payoutFee(s), Side.CREDIT, s, f));
    }

    /** J3b, payout paid with a conversion (S is not T, retries off): the pool takes N and gives Y. */
    public static JournalDraft paidWithConversion(String reference, String s, String t,
                                                  long x, long n, long f, long y) {
        split(x, n, f);
        positive(y, "paid-out amount");
        differ(s, t);
        return draft(reference, "J3b", new Lines()
                .add(LedgerAccounts.inFlight(s), Side.DEBIT, s, x)
                .add(LedgerAccounts.fxPool(s), Side.CREDIT, s, n)
                .addIfPositive(LedgerAccounts.payoutFee(s), Side.CREDIT, s, f)
                .add(LedgerAccounts.fxPool(t), Side.DEBIT, t, y)
                .add(LedgerAccounts.paidOut(t), Side.CREDIT, t, y));
    }

    /** J4, payout failed (retries off, or no conversion was done): everything goes back to the customer. */
    /** J4, payout failed (retries off, or no conversion was done): everything goes back to the customer. */
    public static JournalDraft refund(String reference, String senderAccount, String s, long x) {
        positive(x, "amount");
        return draft(reference, "J4", new Lines()
                .add(LedgerAccounts.inFlight(s), Side.DEBIT, s, x)
                .add(LedgerApi.walletKey(senderAccount, s), Side.CREDIT, s, x));
    }

    /**
     * J6, OWN_LOCK only, after success: the real cost in S differed from the quoted net.
     * {@code differenceMinor} is positive when it cost PayBridge MORE than quoted (RATE_DIFF is debited),
     * negative when it cost less (the two sides swap). Zero is refused: there is nothing to post.
     */
    public static JournalDraft rateDifference(String reference, String s, long differenceMinor) {
        if (differenceMinor == 0) {
            throw new IllegalArgumentException("No rate difference to post.");
        }
        long d = Math.abs(differenceMinor);
        boolean worse = differenceMinor > 0;
        return draft(reference, "J6", new Lines()
                .add(LedgerAccounts.rateDiff(s), worse ? Side.DEBIT : Side.CREDIT, s, d)
                .add(LedgerAccounts.fxPool(s), worse ? Side.CREDIT : Side.DEBIT, s, d));
    }

    /** J2, retries on only: converted but not paid. Y waits in flight in T. */
    public static JournalDraft conversionDone(String reference, String s, String t,
                                              long x, long n, long f, long y) {
        split(x, n, f);
        positive(y, "converted amount");
        differ(s, t);
        return draft(reference, "J2", new Lines()
                .add(LedgerAccounts.inFlight(s), Side.DEBIT, s, x)
                .add(LedgerAccounts.fxPool(s), Side.CREDIT, s, n)
                .addIfPositive(LedgerAccounts.payoutFee(s), Side.CREDIT, s, f)
                .add(LedgerAccounts.fxPool(t), Side.DEBIT, t, y)
                .add(LedgerAccounts.inFlight(t), Side.CREDIT, t, y));
    }

    /** J3c, retries on only: paid after J2. */
    public static JournalDraft paidAfterConversion(String reference, String t, long y) {
        positive(y, "paid-out amount");
        return draft(reference, "J3c", new Lines()
                .add(LedgerAccounts.inFlight(t), Side.DEBIT, t, y)
                .add(LedgerAccounts.paidOut(t), Side.CREDIT, t, y));
    }

    /** J5, retries on only: the third retry failed after J2, so the conversion is undone and the customer repaid. */
    public static JournalDraft refundAfterConversion(String reference, String senderAccount, String s, String t,
                                                     long x, long n, long f, long y) {
        split(x, n, f);
        positive(y, "converted amount");
        differ(s, t);
        return draft(reference, "J5", new Lines()
                .add(LedgerApi.walletKey(senderAccount, s), Side.CREDIT, s, x)
                .add(LedgerAccounts.fxPool(s), Side.DEBIT, s, n)
                .addIfPositive(LedgerAccounts.payoutFee(s), Side.DEBIT, s, f)
                .add(LedgerAccounts.inFlight(t), Side.DEBIT, t, y)
                .add(LedgerAccounts.fxPool(t), Side.CREDIT, t, y));
    }

    // ---------------------------------------------------------------------------------------------

    private static JournalDraft draft(String reference, String phase, Lines lines) {
        return new JournalDraft(key(reference, phase), reference, TYPE, lines.list);
    }

    private static void split(long x, long n, long f) {
        positive(x, "amount");
        positive(n, "net amount");
        if (f < 0) {
            throw new IllegalArgumentException("The fee cannot be negative.");
        }
        if (x != n + f) {
            throw new IllegalArgumentException("Amount must equal net plus fee: " + x + " != " + n + " + " + f);
        }
    }

    private static void positive(long value, String what) {
        if (value <= 0) {
            throw new IllegalArgumentException("The " + what + " must be positive.");
        }
    }

    private static void differ(String s, String t) {
        if (s == null || s.equals(t)) {
            throw new IllegalArgumentException("A conversion needs two different currencies.");
        }
    }

    private static final class Lines {
        private final List<JournalLine> list = new ArrayList<>();

        Lines add(String account, Side side, String currency, long amount) {
            list.add(new JournalLine(account, side, currency, amount));
            return this;
        }

        Lines addIfPositive(String account, Side side, String currency, long amount) {
            return amount > 0 ? add(account, side, currency, amount) : this;
        }
    }
}