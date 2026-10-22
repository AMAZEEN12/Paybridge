package com.academy.paybridge.ledger.api;

import com.academy.paybridge.account.api.AccountApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The five Naira postings that used to be methods on LedgerApi (funding, internal transfer, reserve, payout success,
 * payout refund). Each one now builds its journal and posts it through {@link LedgerApi#recordJournal}.
 *
 * The journal shapes are exactly the old ones: the same journal keys, entry types, line order and amounts, all in NGN.
 * Wallet lines use the "<account>:NGN" key; EXT: accounts keep their names. Amounts are in kobo.
 */
public final class NairaJournals {

    private static final String NGN = "NGN";

    private NairaJournals() {
    }

    /** Money added from outside (development funding). */
    public static void funding(LedgerApi ledger, String accountNumber, long kobo) {
        ledger.recordJournal("FUND:" + UUID.randomUUID(), null, "FUNDING", List.of(
                debit(LedgerApi.EXT_FUNDING, kobo),
                credit(accountNumber, kobo)));
    }

    /** Internal transfer: source pays amount + charges; destination and the charge accounts are credited. */
    public static void internal(LedgerApi ledger, String reference, String source, String destination,
                                long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo) {
        List<JournalLine> lines = new ArrayList<>();
        lines.add(debit(source, amountKobo + feeKobo + vatKobo + stampDutyKobo));
        lines.add(credit(destination, amountKobo));
        addCharges(lines, feeKobo, vatKobo, stampDutyKobo);
        ledger.recordJournal(reference + ":INTERNAL", reference, "TRANSFER", lines);
    }

    /** Payout reserved: the customer is debited and the money waits in EXT:IN_FLIGHT. */
    public static void reserve(LedgerApi ledger, String reference, String source, long totalDebitKobo) {
        ledger.recordJournal(reference + ":RESERVE", reference, "RESERVE", List.of(
                debit(source, totalDebitKobo),
                credit(LedgerApi.EXT_IN_FLIGHT, totalDebitKobo)));
    }

    /** Payout confirmed: EXT:IN_FLIGHT is released to EXT:PAID_OUT and the charge accounts. */
    public static void payoutSuccess(LedgerApi ledger, String reference, long amountKobo, long feeKobo,
                                     long vatKobo, long stampDutyKobo) {
        List<JournalLine> lines = new ArrayList<>();
        lines.add(debit(LedgerApi.EXT_IN_FLIGHT, amountKobo + feeKobo + vatKobo + stampDutyKobo));
        lines.add(credit(LedgerApi.EXT_PAID_OUT, amountKobo));
        addCharges(lines, feeKobo, vatKobo, stampDutyKobo);
        ledger.recordJournal(reference + ":PAYOUT", reference, "PAYOUT", lines);
    }

    /** Payout failed: EXT:IN_FLIGHT goes back to the customer, charges included. */
    public static void payoutRefund(LedgerApi ledger, String reference, String source, long totalDebitKobo) {
        ledger.recordJournal(reference + ":REFUND", reference, "REFUND", List.of(
                debit(LedgerApi.EXT_IN_FLIGHT, totalDebitKobo),
                credit(source, totalDebitKobo)));
    }

    private static void addCharges(List<JournalLine> lines, long fee, long vat, long stamp) {
        if (fee > 0) {
            lines.add(credit(AccountApi.FEE_ACCOUNT, fee));
        }
        if (vat > 0) {
            lines.add(credit(AccountApi.VAT_ACCOUNT, vat));
        }
        if (stamp > 0) {
            lines.add(credit(AccountApi.STAMP_DUTY_ACCOUNT, stamp));
        }
    }

    private static JournalLine debit(String account, long kobo) {
        return new JournalLine(LedgerApi.walletKey(account, NGN), JournalLine.Side.DEBIT, NGN, kobo);
    }

    private static JournalLine credit(String account, long kobo) {
        return new JournalLine(LedgerApi.walletKey(account, NGN), JournalLine.Side.CREDIT, NGN, kobo);
    }
}