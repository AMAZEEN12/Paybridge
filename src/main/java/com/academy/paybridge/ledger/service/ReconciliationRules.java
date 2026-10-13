package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.CurrencyTotals;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongFunction;

/**
 * The reconciliation checks themselves: numbers in, report out. No database and no Spring, so they are easy to test
 * (same idea as FraudRules).
 *
 * Checks:
 *  1. every journal has equal debits and credits
 *  2. every PayBridge wallet's balance equals its ledger total (credits minus debits)
 *  3. the ledger has no lines for a wallet that does not exist
 *  4. money in = money held + money in flight + money paid out (+ the FX pool and fee accounts, once conversions exist)
 *
 * {@link #check} looks at one currency at a time (Naira messages). {@link #checkByCurrency} splits the ledger and
 * the wallets by currency, runs the same checks for each, and joins the results.
 */
public final class ReconciliationRules {

    private ReconciliationRules() {
    }

    /**
     * The keys of ledgerNet and balances only have to match each other. In step 1 they are wallet keys such as
     * "2000000001:NGN"; the older plain account numbers work too.
     *
     * @param unbalancedJournals rows of [journal key, debits, credits] for journals that do not balance
     * @param ledgerNet          wallet key (or EXT: account) to credits minus debits, from the ledger
     * @param balances           wallet key to balance, from the wallets table
     */
    public static ReconciliationReport check(Instant now, long entries, long journals,
                                             List<Object[]> unbalancedJournals,
                                             Map<String, Long> ledgerNet, Map<String, Long> balances) {
        return analyse(now, entries, journals, unbalancedJournals, ledgerNet, balances, Money::format);
    }

    /**
     * The same checks for every currency at once (R-LED-3 and R-LED-4).
     *
     * @param unbalancedJournals rows of [journal key, currency, debits, credits]: one row per journal AND currency
     *                           whose two sides differ
     * @param ledgerNet          rows of [ledger key, currency, credits minus debits]
     * @param balances           wallet key ("2000000001:USD") to balance in that wallet's currency; a key without a
     *                           suffix counts as NGN
     * @param registry           used only to write amounts with the right symbol and decimals; may be null
     * @return one report: problems from every currency (a currency other than NGN is shown as "[USD] ..."), the
     *         *Kobo totals are the Naira totals, and currencies lists each currency's own totals
     */
    public static ReconciliationReport checkByCurrency(Instant now, long entries, long journals,
                                                       List<Object[]> unbalancedJournals, List<Object[]> ledgerNet,
                                                       Map<String, Long> balances, CurrencyRegistry registry) {
        TreeSet<String> currencies = new TreeSet<>();
        currencies.add(NGN);
        for (Object[] row : ledgerNet) {
            currencies.add((String) row[1]);
        }
        for (Object[] row : unbalancedJournals) {
            currencies.add((String) row[1]);
        }
        for (String key : balances.keySet()) {
            currencies.add(currencyOf(key));
        }

        List<String> problems = new ArrayList<>();
        List<CurrencyTotals> totals = new ArrayList<>();
        ReconciliationReport naira = null;
        for (String currency : currencies) {
            Map<String, Long> net = new HashMap<>();
            for (Object[] row : ledgerNet) {
                if (currency.equals(row[1])) {
                    net.put((String) row[0], ((Number) row[2]).longValue());
                }
            }
            Map<String, Long> held = new HashMap<>();
            balances.forEach((key, balance) -> {
                if (currency.equals(currencyOf(key))) {
                    held.put(key, balance);
                }
            });
            List<Object[]> unbalanced = new ArrayList<>();
            for (Object[] row : unbalancedJournals) {
                if (currency.equals(row[1])) {
                    unbalanced.add(new Object[]{row[0], row[2], row[3]});
                }
            }
            ReconciliationReport r = analyse(now, entries, journals, unbalanced, net, held, formatter(currency, registry));
            for (String p : r.problems()) {
                problems.add(NGN.equals(currency) ? p : "[" + currency + "] " + p);
            }
            totals.add(new CurrencyTotals(currency, r.fundedKobo(), r.paidOutKobo(), r.inFlightKobo(), r.heldKobo()));
            if (NGN.equals(currency)) {
                naira = r;
            }
        }
        return new ReconciliationReport(now, problems.isEmpty(), entries, journals, naira.fundedKobo(),
                naira.paidOutKobo(), naira.inFlightKobo(), naira.heldKobo(), List.copyOf(problems), List.copyOf(totals));
    }

    private static final String NGN = "NGN";

    /** The currency a ledger or wallet key belongs to: its ":XXX" suffix, or NGN for a plain account number. */
    private static String currencyOf(String key) {
        int colon = key.lastIndexOf(':');
        if (!key.startsWith("EXT:") && colon >= 0 && key.length() - colon - 1 == 3) {
            return key.substring(colon + 1);
        }
        return NGN;
    }

    private static LongFunction<String> formatter(String currency, CurrencyRegistry registry) {
        if (NGN.equals(currency)) {
            return Money::format;
        }
        return minor -> registry == null ? minor + " " + currency : registry.format(minor, currency);
    }

    private static ReconciliationReport analyse(Instant now, long entries, long journals,
                                                List<Object[]> unbalancedJournals,
                                                Map<String, Long> ledgerNet, Map<String, Long> balances,
                                                LongFunction<String> fmt) {
        List<String> problems = new ArrayList<>();

        for (Object[] j : unbalancedJournals) {
            problems.add("Journal " + j[0] + " does not balance: debits " + fmt.apply(((Number) j[1]).longValue())
                    + ", credits " + fmt.apply(((Number) j[2]).longValue()));
        }

        long held = 0;
        for (String account : new TreeSet<>(balances.keySet())) {
            long balance = balances.get(account);
            long fromLedger = ledgerNet.getOrDefault(account, 0L);
            held += balance;
            if (balance != fromLedger) {
                problems.add("Account " + Money.maskAccount(LedgerApi.accountNumberOf(account)) + ": balance "
                        + fmt.apply(balance) + " but ledger says " + fmt.apply(fromLedger));
            }
        }
        for (String account : new TreeSet<>(ledgerNet.keySet())) {
            if (!account.startsWith("EXT:") && !balances.containsKey(account)) {
                problems.add("Ledger has lines for an unknown account "
                        + Money.maskAccount(LedgerApi.accountNumberOf(account)));
            }
        }

        long funded = -(ledgerNet.getOrDefault(LedgerApi.EXT_OPENING, 0L) + ledgerNet.getOrDefault(LedgerApi.EXT_FUNDING, 0L));
        long paidOut = ledgerNet.getOrDefault(LedgerApi.EXT_PAID_OUT, 0L);
        long inFlight = ledgerNet.getOrDefault(LedgerApi.EXT_IN_FLIGHT, 0L);
        // Conversions (plan step 6) park money in ledger-only FX pool and fee accounts; they are part of the sum.
        long fx = 0;
        for (Map.Entry<String, Long> e : ledgerNet.entrySet()) {
            if (e.getKey().startsWith("EXT:FX_")) {
                fx += e.getValue();
            }
        }
        if (inFlight < 0) {
            problems.add("More money was released from in-flight than was ever reserved (" + fmt.apply(inFlight) + ")");
        }
        if (held + inFlight + paidOut + fx != funded) {
            problems.add("Money does not add up: held " + fmt.apply(held) + " + in flight " + fmt.apply(inFlight)
                    + " + paid out " + fmt.apply(paidOut) + (fx != 0 ? " + FX pool and fee " + fmt.apply(fx) : "")
                    + " should equal funded " + fmt.apply(funded));
        }
        return new ReconciliationReport(now, problems.isEmpty(), entries, journals, funded, paidOut, inFlight, held,
                List.copyOf(problems));
    }
}