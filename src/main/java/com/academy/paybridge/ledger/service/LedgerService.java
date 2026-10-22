package com.academy.paybridge.ledger.service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes balanced journals. Every public method joins the caller's transaction (MANDATORY), the same rule the
 * account module uses for debit and credit, so a balance can never change without its ledger lines.
 */
@Service
public class LedgerService implements LedgerApi {

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern KEY_SUFFIX = Pattern.compile(":([A-Z]{3})$");

    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(LedgerEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordJournal(String journalKey, String reference, String entryType, List<JournalLine> lines) {
        postJournal(journalKey, reference, entryType, lines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConversion(String quoteId, String accountNumber,
                                 String sourceCurrency, String targetCurrency,
                                 long sourceMinor, long feeMinor, long netMinor, long targetMinor) {
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(LedgerApi.walletKey(accountNumber, sourceCurrency),
                JournalLine.Side.DEBIT, sourceCurrency, sourceMinor));
        lines.add(new JournalLine("EXT:FX_POOL:" + sourceCurrency,
                JournalLine.Side.CREDIT, sourceCurrency, netMinor));
        if (feeMinor > 0) {
            lines.add(new JournalLine("EXT:FX_FEE:" + sourceCurrency,
                    JournalLine.Side.CREDIT, sourceCurrency, feeMinor));
        }
        lines.add(new JournalLine("EXT:FX_POOL:" + targetCurrency,
                JournalLine.Side.DEBIT, targetCurrency, targetMinor));
        lines.add(new JournalLine(LedgerApi.walletKey(accountNumber, targetCurrency),
                JournalLine.Side.CREDIT, targetCurrency, targetMinor));

        postJournal("CONV:" + quoteId, quoteId, "CONVERSION", lines);
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
        }

        entries.saveAll(rows);
    }
}