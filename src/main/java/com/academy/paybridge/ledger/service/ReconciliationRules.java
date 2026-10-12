package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The reconciliation checks themselves: numbers in, report out. No database and no Spring, so they are easy to test
 * (same idea as FraudRules).
 *
 * Checks:
 *  1. every journal has equal debits and credits
 *  2. every PayBridge account's balance equals its ledger total (credits minus debits)
 *  3. the ledger has no lines for an account that does not exist
 *  4. money in = money held + money in flight + money paid out
 */
public final class ReconciliationRules {

    private ReconciliationRules() {
    }

    /**
     * @param unbalancedJournals rows of [journal key, debits, credits] for journals that do not balance
     * @param ledgerNet          account (real or EXT:) to credits minus debits, from the ledger
     * @param balances           real account number to balance_kobo, from the accounts table
     */
    public static ReconciliationReport check(Instant now, long entries, long journals,
                                             List<Object[]> unbalancedJournals,
                                             Map<String, Long> ledgerNet, Map<String, Long> balances) {
        List<String> problems = new ArrayList<>();

        for (Object[] j : unbalancedJournals) {
            problems.add("Journal " + j[0] + " does not balance: debits " + Money.format(((Number) j[1]).longValue())
                    + ", credits " + Money.format(((Number) j[2]).longValue()));
        }

        long held = 0;
        for (String account : new TreeSet<>(balances.keySet())) {
            long balance = balances.get(account);
            long fromLedger = ledgerNet.getOrDefault(account, 0L);
            held += balance;
            if (balance != fromLedger) {
                problems.add("Account " + Money.maskAccount(account) + ": balance " + Money.format(balance)
                        + " but ledger says " + Money.format(fromLedger));
            }
        }
        for (String account : new TreeSet<>(ledgerNet.keySet())) {
            if (!account.startsWith("EXT:") && !balances.containsKey(account)) {
                problems.add("Ledger has lines for an unknown account " + Money.maskAccount(account));
            }
        }

        long funded = -(ledgerNet.getOrDefault(LedgerApi.EXT_OPENING, 0L) + ledgerNet.getOrDefault(LedgerApi.EXT_FUNDING, 0L));
        long paidOut = ledgerNet.getOrDefault(LedgerApi.EXT_PAID_OUT, 0L);
        long inFlight = ledgerNet.getOrDefault(LedgerApi.EXT_IN_FLIGHT, 0L);
        if (inFlight < 0) {
            problems.add("More money was released from in-flight than was ever reserved (" + Money.format(inFlight) + ")");
        }
        if (held + inFlight + paidOut != funded) {
            problems.add("Money does not add up: held " + Money.format(held) + " + in flight " + Money.format(inFlight)
                    + " + paid out " + Money.format(paidOut) + " should equal funded " + Money.format(funded));
        }
        return new ReconciliationReport(now, problems.isEmpty(), entries, journals, funded, paidOut, inFlight, held,
                List.copyOf(problems));
    }
}