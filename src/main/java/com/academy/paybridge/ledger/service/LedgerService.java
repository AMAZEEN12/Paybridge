package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.domain.Direction;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes balanced journals. Every public method joins the caller's transaction (MANDATORY), the same rule the
 * account module uses for debit and credit, so a balance can never change without its ledger lines.
 */
@Service
public class LedgerService implements LedgerApi {
    /** Step 1 is NGN only; the currency-aware journal methods arrive in step 2. */
    private static final String NGN = "NGN";

    /** The older methods below (funding, transfers, payouts) are Naira only; recordJournal handles any currency. */
    private static final String NGN = "NGN";
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern KEY_SUFFIX = Pattern.compile(":([A-Z]{3})$");

    private record Line(String account, Direction direction, long kobo) {
    }

    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(LedgerEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFunding(String accountNumber, long kobo) {
        post("FUND:" + UUID.randomUUID(), null, "FUNDING", List.of(
                new Line(EXT_FUNDING, Direction.DEBIT, kobo),
                new Line(accountNumber, Direction.CREDIT, kobo)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInternal(String reference, String source, String destination,
                               long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(source, Direction.DEBIT, amountKobo + feeKobo + vatKobo + stampDutyKobo));
        lines.add(new Line(destination, Direction.CREDIT, amountKobo));
        addCharges(lines, feeKobo, vatKobo, stampDutyKobo);
        post(reference + ":INTERNAL", reference, "TRANSFER", lines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReserve(String reference, String source, long totalDebitKobo) {
        post(reference + ":RESERVE", reference, "RESERVE", List.of(
                new Line(source, Direction.DEBIT, totalDebitKobo),
                new Line(EXT_IN_FLIGHT, Direction.CREDIT, totalDebitKobo)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPayoutSuccess(String reference, long amountKobo, long feeKobo, long vatKobo,
                                    long stampDutyKobo) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(EXT_IN_FLIGHT, Direction.DEBIT,
                amountKobo + feeKobo + vatKobo + stampDutyKobo));
        lines.add(new Line(EXT_PAID_OUT, Direction.CREDIT, amountKobo));
        addCharges(lines, feeKobo, vatKobo, stampDutyKobo);
        post(reference + ":PAYOUT", reference, "PAYOUT", lines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPayoutRefund(String reference, String source, long totalDebitKobo) {
        post(reference + ":REFUND", reference, "REFUND", List.of(
                new Line(EXT_IN_FLIGHT, Direction.DEBIT, totalDebitKobo),
                new Line(source, Direction.CREDIT, totalDebitKobo)));
    }

    private static void addCharges(List<Line> lines, long fee, long vat, long stamp) {
        if (fee > 0) {
            lines.add(new Line(AccountApi.FEE_ACCOUNT, Direction.CREDIT, fee));
        }
        if (vat > 0) {
            lines.add(new Line(AccountApi.VAT_ACCOUNT, Direction.CREDIT, vat));
        }
        if (stamp > 0) {
            lines.add(new Line(AccountApi.STAMP_DUTY_ACCOUNT, Direction.CREDIT, stamp));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordJournal(String journalKey, String reference, String entryType, List<JournalLine> lines) {
        postJournal(journalKey, reference, entryType, lines);
    }

    /** The Naira-only methods convert real accounts to NGN wallet keys; EXT: accounts keep their names. */
    private void post(String journalKey, String reference, String type, List<Line> lines) {
        List<JournalLine> converted = new ArrayList<>();
        for (Line l : lines) {
            converted.add(new JournalLine(
                    LedgerApi.walletKey(l.account(), NGN),
                    l.direction() == Direction.DEBIT ? JournalLine.Side.DEBIT : JournalLine.Side.CREDIT,
                    NGN,
                    l.kobo()));
        }
        postJournal(journalKey, reference, type, converted);
    }

    /**
     * Refuses to save a journal in which any currency's two sides differ.
     * A USD line can never be balanced by a EUR line.
     */
    private void postJournal(String journalKey, String reference, String type, List<JournalLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalStateException("A journal needs at least one line: " + journalKey);
        }

        Map<String, long[]> totals = new TreeMap<>(); // currency -> [debits, credits]

        for (JournalLine l : lines) {
            if (l.amountMinor() <= 0) {
                throw new IllegalStateException("Ledger line must be positive: " + journalKey);
            }

            if (l.currency() == null || !CURRENCY_CODE.matcher(l.currency()).matches()) {
                throw new IllegalStateException("Ledger line has no valid currency: " + journalKey);
            }

            Matcher suffix = l.account() == null ? null : KEY_SUFFIX.matcher(l.account());
            if (suffix != null && suffix.find() && !suffix.group(1).equals(l.currency())) {
                throw new IllegalStateException("Ledger line " + l.account() + " is in " + l.currency()
                        + " but its key says " + suffix.group(1) + ": " + journalKey);
            }

            long[] t = totals.computeIfAbsent(l.currency(), c -> new long[2]);
            int side = l.side() == JournalLine.Side.DEBIT ? 0 : 1;
            t[side] = Math.addExact(t[side], l.amountMinor());
        }

        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            if (e.getValue()[0] != e.getValue()[1]) {
                throw new IllegalStateException("Unbalanced journal " + journalKey + " in " + e.getKey()
                        + ": debits " + e.getValue()[0] + " credits " + e.getValue()[1]);
            }
        }

        var now = clock.instant();
        List<LedgerEntry> rows = new ArrayList<>();
<<<<<<< HEAD

        for (JournalLine l : lines) {
            rows.add(new LedgerEntry(
                    journalKey,
                    reference,
                    l.account(),
                    Direction.valueOf(l.side().name()),
                    l.amountMinor(),
                    l.currency(),
                    type,
                    now));
=======
        for (Line l : lines) {
            // Real accounts become wallet keys ("2000000001:NGN"); the EXT: accounts keep their names.
            rows.add(new LedgerEntry(journalKey, reference, LedgerApi.walletKey(l.account(), NGN),
                    l.direction(), l.kobo(), NGN, type, now));
>>>>>>> origin/main
        }

        entries.saveAll(rows);
    }
}
