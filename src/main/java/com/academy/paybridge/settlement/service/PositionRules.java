package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.AccountPosition;
import com.academy.paybridge.ledger.api.LedgerAccounts;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.settlement.api.PositionReport;
import com.academy.paybridge.settlement.api.PositionReport.CurrencyPositions;
import com.academy.paybridge.settlement.api.PositionReport.Position;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * Pure position report: ledger rows and thresholds in, report out. No database, no Spring (R-SET-1, R-SET-2).
 * Sign: credits minus debits. An account that has no lines yet shows 0.
 */
public final class PositionRules {

    private static final String NGN = "NGN";

    private PositionRules() {
    }

    private record Slot(String name, String account) {
    }

    /** @param format turns (minor units, currency) into text for people, for example "$1,234.50" */
    public static PositionReport build(Instant now, List<AccountPosition> rows,
                                       List<SettlementProperties.Threshold> thresholds,
                                       BiFunction<Long, String, String> format) {
        Map<String, Long> net = new HashMap<>();
        TreeSet<String> currencies = new TreeSet<>();
        currencies.add(NGN);
        for (AccountPosition p : rows) {
            net.merge(key(p.account(), p.currency()), p.netMinor(), Long::sum);
            if (tracked(p.account())) {
                currencies.add(p.currency());
            }
        }
        Map<String, Long> minimums = new HashMap<>();
        for (SettlementProperties.Threshold t : thresholds) {
            minimums.put(t.account(), t.minMinor());
        }

        List<CurrencyPositions> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String currency : currencies) {
            List<Position> positions = new ArrayList<>();
            for (Slot slot : slots(currency)) {
                long value = net.getOrDefault(key(slot.account(), currency), 0L);
                Long min = minimums.get(slot.account());
                boolean below = min != null && value < min;
                String shown = format.apply(value, currency);
                positions.add(new Position(slot.name(), slot.account(), value, shown, min, below));
                if (below) {
                    warnings.add(slot.account() + " is " + shown + ", below its minimum of " + format.apply(min, currency));
                }
            }
            result.add(new CurrencyPositions(currency, positions));
        }
        return new PositionReport(now, result, warnings);
    }

    /** The accounts R-SET-1 lists for one currency. Tax payable and the three system wallets are Naira only. */
    private static List<Slot> slots(String c) {
        List<Slot> s = new ArrayList<>(List.of(
                new Slot("FX pool", LedgerAccounts.fxPool(c)),
                new Slot("FX fee", LedgerAccounts.fxFee(c)),
                new Slot("Payout fee", LedgerAccounts.payoutFee(c)),
                new Slot("Rate difference", LedgerAccounts.rateDiff(c)),
                new Slot("In flight", LedgerAccounts.inFlight(c)),
                new Slot("Paid out", LedgerAccounts.paidOut(c)),
                new Slot("Revenue", LedgerAccounts.revenue(c))));
        if (NGN.equals(c)) {
            s.add(new Slot("Tax payable", LedgerAccounts.taxPayable()));
            s.add(new Slot("Fee account (system)", LedgerApi.walletKey(AccountApi.FEE_ACCOUNT, NGN)));
            s.add(new Slot("VAT account (system)", LedgerApi.walletKey(AccountApi.VAT_ACCOUNT, NGN)));
            s.add(new Slot("Stamp duty account (system)", LedgerApi.walletKey(AccountApi.STAMP_DUTY_ACCOUNT, NGN)));
        }
        return s;
    }

    /** An outside account of a known family; funding and opening are not positions. */
    private static boolean tracked(String account) {
        return LedgerAccounts.isKnownExternal(account)
                && !LedgerApi.EXT_OPENING.equals(account) && !LedgerApi.EXT_FUNDING.equals(account);
    }

    private static String key(String account, String currency) {
        return account + "|" + currency;
    }
}