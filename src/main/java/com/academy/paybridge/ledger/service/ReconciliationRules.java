
package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.CurrencyTotals;
import com.academy.paybridge.ledger.api.LedgerAccounts;
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
 * The reconciliation checks themselves: numbers in, report out.
 * No database and no Spring, so they are easy to test.
 *
 * Checks:
 * 1. Every journal has equal debits and credits.
 * 2. Every PayBridge wallet's balance equals its ledger total.
 * 3. The ledger has no lines for a wallet that does not exist, or for an outside account nobody knows.
 * 4. Money in = money held + money in flight + money paid out + the ledger-only accounts
 *    (FX pool and fee, payout fee, rate difference, revenue, tax payable).
 */
public final class ReconciliationRules {

    private static final String NGN = "NGN";

    /** Ledger-only accounts that hold PayBridge's own money or its obligations; counted as "other" in check 4. */
    private static final String[] OTHER_PREFIXES = {
            "EXT:PAYOUT_FEE", "EXT:RATE_DIFF", "EXT:REVENUE", "EXT:TAX_PAYABLE"
    };

    private ReconciliationRules() {
    }

    /**
     * The keys of ledgerNet and balances only have to match each other.
     * Wallet keys such as "2000000001:NGN" are supported,
     * as are legacy plain account numbers.
     */
    public static ReconciliationReport check(
            Instant now,
            long entries,
            long journals,
            List<Object[]> unbalancedJournals,
            Map<String, Long> ledgerNet,
            Map<String, Long> balances) {

        return analyse(
                now,
                entries,
                journals,
                unbalancedJournals,
                ledgerNet,
                balances,
                Money::format,
                NGN
        );
    }

    /**
     * Runs reconciliation independently for each currency.
     *
     * @param unbalancedJournals rows of [journal key, currency, debits, credits]
     * @param ledgerNet rows of [account number or ledger key, currency, credits minus debits]
     * @param balances wallet key to balance, such as "2000000001:USD"
     * @param registry formats amounts in each currency; may be null
     */
    public static ReconciliationReport checkByCurrency(
            Instant now,
            long entries,
            long journals,
            List<Object[]> unbalancedJournals,
            List<Object[]> ledgerNet,
            Map<String, Long> balances,
            CurrencyRegistry registry) {

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
                    String account = (String) row[0];

                    // Preserve keys that already contain a currency suffix.
                    // External ledger accounts must remain unchanged.
                    String key;
                    if (account.startsWith("EXT:")
                            || account.endsWith(":" + currency)) {
                        key = account;
                    } else {
                        key = LedgerApi.walletKey(account, currency);
                    }

                    long amount = ((Number) row[2]).longValue();
                    net.put(key, amount);
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

            ReconciliationReport report = analyse(
                    now,
                    entries,
                    journals,
                    unbalanced,
                    net,
                    held,
                    formatter(currency, registry),
                    currency
            );

            for (String problem : report.problems()) {
                problems.add(
                        NGN.equals(currency)
                                ? problem
                                : "[" + currency + "] " + problem
                );
            }

            totals.add(new CurrencyTotals(
                    currency,
                    report.fundedKobo(),
                    report.paidOutKobo(),
                    report.inFlightKobo(),
                    report.heldKobo()
            ));

            if (NGN.equals(currency)) {
                naira = report;
            }
        }

        if (naira == null) {
            throw new IllegalStateException(
                    "NGN reconciliation report was not generated"
            );
        }

        return new ReconciliationReport(
                now,
                problems.isEmpty(),
                entries,
                journals,
                naira.fundedKobo(),
                naira.paidOutKobo(),
                naira.inFlightKobo(),
                naira.heldKobo(),
                List.copyOf(problems),
                List.copyOf(totals)
        );
    }

    /**
     * The currency a wallet key belongs to: its :XXX suffix,
     * or NGN for a plain account number.
     */
    private static String currencyOf(String key) {
        int colon = key.lastIndexOf(':');

        if (!key.startsWith("EXT:")
                && colon >= 0
                && key.length() - colon - 1 == 3) {
            return key.substring(colon + 1);
        }

        return NGN;
    }

    private static LongFunction<String> formatter(
            String currency,
            CurrencyRegistry registry) {

        if (NGN.equals(currency)) {
            return Money::format;
        }

        return minor -> registry == null
                ? minor + " " + currency
                : registry.format(minor, currency);
    }

    /** The net of several named accounts (an absent one counts as zero). */
    private static long sum(Map<String, Long> ledgerNet, String... accounts) {
        long total = 0;
        for (String account : accounts) {
            total += ledgerNet.getOrDefault(account, 0L);
        }
        return total;
    }

    private static ReconciliationReport analyse(
            Instant now,
            long entries,
            long journals,
            List<Object[]> unbalancedJournals,
            Map<String, Long> ledgerNet,
            Map<String, Long> balances,
            LongFunction<String> fmt,
            String currency) {

        List<String> problems = new ArrayList<>();

        for (Object[] journal : unbalancedJournals) {
            problems.add(
                    "Journal " + journal[0]
                            + " does not balance: debits "
                            + fmt.apply(((Number) journal[1]).longValue())
                            + ", credits "
                            + fmt.apply(((Number) journal[2]).longValue())
            );
        }

        long held = 0;

        for (String account : new TreeSet<>(balances.keySet())) {
            long balance = balances.get(account);
            long fromLedger = ledgerNet.getOrDefault(account, 0L);
            held += balance;

            if (balance != fromLedger) {
                problems.add(
                        "Account "
                                + Money.maskAccount(
                                LedgerApi.accountNumberOf(account)
                        )
                                + ": balance "
                                + fmt.apply(balance)
                                + " but ledger says "
                                + fmt.apply(fromLedger)
                );
            }
        }

        for (String account : new TreeSet<>(ledgerNet.keySet())) {
            if (account.startsWith("EXT:")) {
                if (!LedgerAccounts.isKnownExternal(account)) {
                    problems.add("Ledger has lines for an unknown outside account " + account);
                }
            } else if (!balances.containsKey(account)) {
                problems.add(
                        "Ledger has lines for an unknown account "
                                + Money.maskAccount(
                                LedgerApi.accountNumberOf(account)
                        )
                );
            }
        }

        long funded = -(
                ledgerNet.getOrDefault(LedgerApi.EXT_OPENING, 0L)
                        + ledgerNet.getOrDefault(LedgerApi.EXT_FUNDING, 0L)
        );

        // The Naira names have no suffix; the other currencies end in :XXX. Both count, in whichever currency this is.
        long paidOut = sum(ledgerNet, LedgerApi.EXT_PAID_OUT, "EXT:PAID_OUT:" + currency);
        long inFlight = sum(ledgerNet, LedgerApi.EXT_IN_FLIGHT, "EXT:IN_FLIGHT:" + currency);

        long fx = 0;
        long other = 0;

        for (Map.Entry<String, Long> entry : ledgerNet.entrySet()) {
            String account = entry.getKey();
            if (account.startsWith("EXT:FX_")) {
                fx += entry.getValue();
            }
            for (String prefix : OTHER_PREFIXES) {
                if (account.equals(prefix) || account.startsWith(prefix + ":")) {
                    other += entry.getValue();
                }
            }
        }

        if (inFlight < 0) {
            problems.add(
                    "More money was released from in-flight than was ever reserved ("
                            + fmt.apply(inFlight) + ")"
            );
        }

        if (held + inFlight + paidOut + fx + other != funded) {
            problems.add(
                    "Money does not add up: held "
                            + fmt.apply(held)
                            + " + in flight "
                            + fmt.apply(inFlight)
                            + " + paid out "
                            + fmt.apply(paidOut)
                            + (fx != 0
                            ? " + FX pool and fee " + fmt.apply(fx)
                            : "")
                            + (other != 0
                            ? " + payout fees and revenue " + fmt.apply(other)
                            : "")
                            + " should equal funded "
                            + fmt.apply(funded)
            );
        }

        return new ReconciliationReport(
                now,
                problems.isEmpty(),
                entries,
                journals,
                funded,
                paidOut,
                inFlight,
                held,
                List.copyOf(problems)
        );
    }
}