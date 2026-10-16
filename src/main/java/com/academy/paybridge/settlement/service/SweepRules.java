package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.AccountPosition;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.JournalLine.Side;
import com.academy.paybridge.ledger.api.LedgerAccounts;
import com.academy.paybridge.ledger.api.LedgerApi;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Pure sweep rules (R-SET-11): which accounts move where, and the journal lines. No database, no Spring. */
public final class SweepRules {

    private static final String NGN = "NGN";

    private SweepRules() {
    }

    /**
     * @param source        ledger key of the account to empty
     * @param destination   ledger key that receives it
     * @param walletAccount the account number whose NGN wallet must also be debited, or null for a ledger-only account
     */
    public record Plan(String source, String currency, String destination, String walletAccount) {
    }

    /** Every sweep that could apply. NGN is always included; other currencies appear once a fee line exists. */
    public static List<Plan> plans(List<AccountPosition> rows, String onlyCurrency) {
        TreeSet<String> currencies = new TreeSet<>();
        currencies.add(NGN);
        for (AccountPosition p : rows) {
            if (p.account().startsWith("EXT:FX_FEE:") || p.account().startsWith("EXT:PAYOUT_FEE:")) {
                currencies.add(p.currency());
            }
        }
        List<Plan> plans = new ArrayList<>();
        for (String c : currencies) {
            if (onlyCurrency != null && !onlyCurrency.equals(c)) {
                continue;
            }
            plans.add(new Plan(LedgerAccounts.fxFee(c), c, LedgerAccounts.revenue(c), null));
            plans.add(new Plan(LedgerAccounts.payoutFee(c), c, LedgerAccounts.revenue(c), null));
            if (NGN.equals(c)) {
                plans.add(wallet(AccountApi.FEE_ACCOUNT, LedgerAccounts.revenue(NGN)));
                plans.add(wallet(AccountApi.VAT_ACCOUNT, LedgerAccounts.taxPayable()));
                plans.add(wallet(AccountApi.STAMP_DUTY_ACCOUNT, LedgerAccounts.taxPayable()));
            }
        }
        return plans;
    }

    private static Plan wallet(String accountNumber, String destination) {
        return new Plan(LedgerApi.walletKey(accountNumber, NGN), NGN, destination, accountNumber);
    }

    /** Credits minus debits of the source, or 0 if it holds nothing (a negative position is never swept). */
    public static long amountOf(List<AccountPosition> rows, Plan plan) {
        long net = 0;
        for (AccountPosition p : rows) {
            if (p.account().equals(plan.source()) && p.currency().equals(plan.currency())) {
                net += p.netMinor();
            }
        }
        return Math.max(net, 0L);
    }

    /** The balanced journal: the source is debited, the destination credited, same currency, same amount. */
    public static List<JournalLine> lines(Plan plan, long amountMinor) {
        return List.of(
                new JournalLine(plan.source(), Side.DEBIT, plan.currency(), amountMinor),
                new JournalLine(plan.destination(), Side.CREDIT, plan.currency(), amountMinor));
    }
}