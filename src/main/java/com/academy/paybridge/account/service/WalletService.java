package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.Wallet;
import com.academy.paybridge.account.domain.WalletStatus;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.repository.WalletRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** Swap: activate a currency and, when all 4 slots are taken, park one non-home wallet (R-WAL-4 to R-WAL-10). */
@Service
public class WalletService {

    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final CurrencyRegistry currencies;
    private final AuditService audit;
    private final FundingDetailsService fundingDetails;

    public WalletService(AccountRepository accounts, WalletRepository wallets, CurrencyRegistry currencies,
                         AuditService audit, FundingDetailsService fundingDetails) {
        this.accounts = accounts;
        this.wallets = wallets;
        this.currencies = currencies;
        this.audit = audit;
        this.fundingDetails = fundingDetails;
    }

    /**
     * The caller passes the account number so that THIS transaction starts with the row lock: the wallets are read
     * only after the lock is held, so a second simultaneous swap sees the first one's result (R-WAL-7).
     */
    @Transactional
    public List<WalletView> swap(Long customerId, String accountNumber, String currency, String parkCurrency) {
        String target = normalise(currency);
        String park = normalise(parkCurrency);
        if (target == null) {
            throw ApiException.badRequest("INVALID_CURRENCY", "Choose a currency to activate.");
        }
        currencies.require(target);          // unknown, disabled and excluded currencies are all refused

        Account account = accounts.findForUpdate(accountNumber)
                .filter(a -> a.getType() == AccountType.CUSTOMER && a.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));

        if (account.getStatus() == AccountStatus.FROZEN) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to change wallets.");
        }

        List<WalletRules.WalletState> states = account.getWallets().stream()
                .map(w -> new WalletRules.WalletState(w.getCurrency(), w.getStatus() == WalletStatus.ACTIVE, w.isHome()))
                .toList();
        WalletRules.SwapPlan plan = WalletRules.planSwap(states, target, park);

        String who = "customer:" + customerId;
        String where = "account " + Money.maskAccount(accountNumber) + ", ";

        if (plan.park() != null) {
            Wallet toPark = find(account, plan.park());
            toPark.setStatus(WalletStatus.PARKED);
            wallets.save(toPark);
            audit.record(who, "WALLET_PARKED", null, where + plan.park());
        }

        if (plan.reactivate()) {
            Wallet back = find(account, target);
            back.setStatus(WalletStatus.ACTIVE);
            wallets.save(back);
            audit.record(who, "WALLET_REACTIVATED", null, where + target);
        } else {
            Wallet created = new Wallet(account, target, false);
            account.addWallet(created);
            wallets.save(created);
            fundingDetails.ensure(account, created);   // a new wallet gets its account details straight away
            audit.record(who, "WALLET_ACTIVATED", null, where + target);
        }
        return WalletQueryService.viewsOf(account);
    }

    private static Wallet find(Account account, String currency) {
        return account.getWallets().stream().filter(w -> w.getCurrency().equals(currency)).findFirst().orElseThrow();
    }

    private static String normalise(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}