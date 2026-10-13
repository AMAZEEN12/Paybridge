package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
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
import java.util.UUID;

/**
 * Writes balanced journals. Every public method joins the caller's transaction (MANDATORY), the same rule the
 * account module uses for debit and credit, so a balance can never change without its ledger lines.
 */
@Service
public class LedgerService implements LedgerApi {
    /** Step 1 is NGN only; the currency-aware journal methods arrive in step 2. */
    private static final String NGN = "NGN";

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
    public void recordPayoutSuccess(String reference, long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(EXT_IN_FLIGHT, Direction.DEBIT, amountKobo + feeKobo + vatKobo + stampDutyKobo));
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

    /** Refuses to save a journal whose two sides differ. This is the double-entry rule itself. */
    private void post(String journalKey, String reference, String type, List<Line> lines) {
        long debits = 0;
        long credits = 0;
        for (Line l : lines) {
            if (l.kobo() <= 0) {
                throw new IllegalStateException("Ledger line must be positive: " + journalKey);
            }
            if (l.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, l.kobo());
            } else {
                credits = Math.addExact(credits, l.kobo());
            }
        }
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced journal " + journalKey + ": debits " + debits + " credits " + credits);
        }
        var now = clock.instant();
        List<LedgerEntry> rows = new ArrayList<>();
        for (Line l : lines) {
            // Real accounts become wallet keys ("2000000001:NGN"); the EXT: accounts keep their names.
            rows.add(new LedgerEntry(journalKey, reference, LedgerApi.walletKey(l.account(), NGN),
                    l.direction(), l.kobo(), NGN, type, now));
        }
        entries.saveAll(rows);
    }
}