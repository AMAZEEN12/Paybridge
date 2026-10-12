package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

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

    public ReconciliationService(LedgerEntryRepository entries, AccountApi accounts, Clock clock) {
        this.entries = entries;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        Map<String, Long> net = new HashMap<>();
        for (Object[] row : entries.netByAccount()) {
            net.put((String) row[0], ((Number) row[1]).longValue());
        }
        return ReconciliationRules.check(clock.instant(), entries.count(), entries.countJournals(),
                entries.findUnbalancedJournals(), net, accounts.balances());
    }
}