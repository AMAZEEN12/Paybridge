package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.Wallet;
import com.academy.paybridge.account.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only: lists a customer's wallets. Changes nothing. */
@Service
public class WalletQueryService {

    private final AccountRepository accounts;

    public WalletQueryService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<WalletView> forCustomer(Long customerId) {
        List<WalletView> result = new ArrayList<>();
        for (Account a : accounts.findByCustomerIdOrderByIdAsc(customerId)) {
            if (a.getType() != AccountType.CUSTOMER) {
                continue;
            }
            for (Wallet w : a.getWallets()) {
                result.add(new WalletView(a.getAccountNumber(), w.getCurrency(), w.getBalanceMinor(),
                        w.getStatus().name(), w.isHome()));
            }
        }
        // Home wallet first, then the others by currency code.
        result.sort(Comparator.comparing(WalletView::accountNumber)
                .thenComparing(w -> !w.home())
                .thenComparing(WalletView::currency));
        return result;
    }
}