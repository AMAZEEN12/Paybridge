package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Compares the ledger with the account balances.
 *
 * It runs in ONE read-only transaction with REPEATABLE_READ, so both sides are read from the same snapshot of the
 * database. Without that, a transfer that commits between the two reads would look like a mismatch.
 */
@Service
public class ReconciliationService {

    private final LedgerEntryRepository entries;
    private final AccountApi accounts;
    private final Clock clock;
    private final CurrencyRegistry currencies;

    public ReconciliationService(LedgerEntryRepository entries, AccountApi accounts, Clock clock,
                                 CurrencyRegistry currencies) {
        this.entries = entries;
        this.accounts = accounts;
        this.clock = clock;
        this.currencies = currencies;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        return ReconciliationRules.checkByCurrency(clock.instant(), entries.count(), entries.countJournals(),
                entries.findUnbalancedJournalsByCurrency(), entries.netByAccountAndCurrency(),
                accounts.balances(), currencies);
    }
}