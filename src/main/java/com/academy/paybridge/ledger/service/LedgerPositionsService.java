package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.AccountPosition;
import com.academy.paybridge.ledger.api.LedgerPositionsApi;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LedgerPositionsService implements LedgerPositionsApi {

    private final LedgerEntryRepository entries;

    public LedgerPositionsService(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    /** One query, so every position comes from the same snapshot. Rows are [account, currency, credits minus debits]. */
    @Override
    @Transactional(readOnly = true)
    public List<AccountPosition> positions() {
        return entries.netByAccountAndCurrency().stream()
                .map(r -> new AccountPosition((String) r[0], (String) r[1], ((Number) r[2]).longValue()))
                .toList();
    }
}