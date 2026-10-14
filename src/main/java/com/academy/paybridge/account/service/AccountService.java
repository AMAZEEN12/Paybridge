package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountStatusChangedEvent;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.Wallet;
import com.academy.paybridge.account.domain.WalletStatus;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.repository.WalletRepository;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.shared.money.Money;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class AccountService implements AccountApi {

    /** Every account also gets these wallets (R-WAL-2), unless one of them is the home currency. */
    private static final List<String> STARTER_CURRENCIES = List.of("USD", "EUR", "GBP");

    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final LedgerApi ledger;
    private final CurrencyRegistry currencies;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accounts, WalletRepository wallets, AuditService audit,
                          ApplicationEventPublisher events, LedgerApi ledger, CurrencyRegistry currencies) {
        this.accounts = accounts;
        this.wallets = wallets;
        this.audit = audit;
        this.events = events;
        this.ledger = ledger;
        this.currencies = currencies;
    }

    // ---------- sign-up ----------

    @Override
    @Transactional
    public AccountView openHomeAccount(Long customerId, String homeCurrency) {
        if (!currencies.isUsable(homeCurrency)) {
            throw ApiException.unprocessable("CURRENCY_NOT_SUPPORTED", "That currency is not supported.");
        }
        if (accounts.countByCustomerIdAndType(customerId, AccountType.CUSTOMER) > 0) {
            throw ApiException.conflict("ACCOUNT_EXISTS", "You already have an account.");
        }
        String number = newAccountNumber();
        Account account = new Account(number, customerId);
        account.addWallet(new Wallet(account, homeCurrency, true));
        for (String currency : STARTER_CURRENCIES) {
            if (!currency.equals(homeCurrency)) {
                account.addWallet(new Wallet(account, currency, false));
            }
        }
        Account saved = accounts.saveAndFlush(account);
        audit.record("customer:" + customerId, "ACCOUNT_OPENED", null,
                "account " + Money.maskAccount(number) + ", home " + homeCurrency);
        return view(saved);
    }

    // ---------- used by the account controller ----------

    /** The customer's account. Every customer has exactly one, created at sign-up. */
    @Transactional(readOnly = true)
    public AccountView existingFor(Long customerId) {
        return listFor(customerId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
    }

    @Transactional(readOnly = true)
    public List<AccountView> listFor(Long customerId) {
        return accounts.findByCustomerIdOrderByIdAsc(customerId).stream()
                .filter(a -> a.getType() == AccountType.CUSTOMER)
                .map(AccountService::view).toList();
    }

    @Transactional
    public AccountView setFrozen(String accountNumber, Long customerId, boolean frozen) {
        // validate ownership first (404 if not yours)
        getOwned(accountNumber, customerId);
        lockInOrder(List.of(accountNumber));
        Account a = accounts.findForUpdate(accountNumber).orElseThrow();
        a.setStatus(frozen ? AccountStatus.FROZEN : AccountStatus.ACTIVE);
        accounts.save(a);
        audit.record("customer:" + customerId, frozen ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN", null,
                "account " + Money.maskAccount(accountNumber));
        events.publishEvent(new AccountStatusChangedEvent(accountNumber, customerId, a.getStatus()));
        return view(a);
    }

    /** Development only: Naira from nowhere. The controller that calls this is switched off in production. */
    @Transactional
    public AccountView devFund(String accountNumber, Long customerId, long kobo) {
        getOwned(accountNumber, customerId);
        credit(accountNumber, kobo);
        ledger.recordFunding(accountNumber, kobo);
        audit.record("customer:" + customerId, "DEV_FUNDING", null,
                "account " + Money.maskAccount(accountNumber) + " +" + kobo + " kobo");
        return view(accounts.findByAccountNumber(accountNumber).orElseThrow());
    }

    /** Development only: money from nowhere in any currency. A parked wallet can be funded too (R-DEV-1). */
    @Transactional
    public AccountView devFund(String accountNumber, Long customerId, String currency, long minor) {
        getOwned(accountNumber, customerId);
        credit(accountNumber, currency, minor);
        ledger.recordJournal("FUND:" + UUID.randomUUID(), null, "FUNDING", List.of(
                new JournalLine(LedgerApi.EXT_FUNDING, JournalLine.Side.DEBIT, currency, minor),
                new JournalLine(LedgerApi.walletKey(accountNumber, currency), JournalLine.Side.CREDIT,
                        currency, minor)));
        audit.record("customer:" + customerId, "DEV_FUNDING", null,
                "account " + Money.maskAccount(accountNumber) + " +" + minor + " " + currency + " (minor units)");
        return view(accounts.findByAccountNumber(accountNumber).orElseThrow());
    }

    // ---------- AccountApi ----------

    @Override
    @Transactional(readOnly = true)
    public AccountView getOwned(String accountNumber, Long customerId) {
        Account a = accounts.findByAccountNumber(accountNumber)
                .filter(x -> x.getType() == AccountType.CUSTOMER && x.getCustomerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        return view(a);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView getCustomerAccount(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .filter(x -> x.getType() == AccountType.CUSTOMER)
                .map(AccountService::view)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> ownerOf(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .filter(x -> x.getType() == AccountType.CUSTOMER)
                .map(Account::getCustomerId);
    }

    /** Locks the NGN wallet row of each account, in sorted account-number order, no duplicates. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInOrder(Collection<String> accountNumbers) {
        for (String number : new TreeSet<>(accountNumbers)) {
            wallets.findNgnForUpdate(number)
                    .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(String accountNumber, long kobo) {
        Wallet w = wallets.findNgnForUpdate(accountNumber)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        checkCanDebit(w, kobo);
        w.setBalanceMinor(w.getBalanceMinor() - kobo);
        wallets.save(w);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(String accountNumber, long kobo) {
        Wallet w = wallets.findNgnForUpdate(accountNumber)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        w.setBalanceMinor(Math.addExact(w.getBalanceMinor(), kobo));
        wallets.save(w);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(String accountNumber, String currency, long minor) {
        Wallet w = wallets.findForUpdate(accountNumber, currency)
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "Wallet not found."));
        checkCanDebit(w, minor);
        w.setBalanceMinor(w.getBalanceMinor() - minor);
        wallets.save(w);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(String accountNumber, String currency, long minor) {
        if (minor <= 0) {
            throw new IllegalArgumentException("A credit must be positive.");
        }
        Wallet w = wallets.findForUpdate(accountNumber, currency)
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "Wallet not found."));
        w.setBalanceMinor(Math.addExact(w.getBalanceMinor(), minor));     // a parked wallet may receive
        wallets.save(w);
    }

    /** Frozen account, parked wallet and too little money all stop a debit (R-WAL-6, R-WAL-8). */
    private static void checkCanDebit(Wallet w, long minor) {
        if (minor <= 0) {
            throw new IllegalArgumentException("A debit must be positive.");
        }
        if (w.getAccount().getStatus() == AccountStatus.FROZEN) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to send money.");
        }
        if (w.getStatus() == WalletStatus.PARKED) {
            throw ApiException.unprocessable("WALLET_PARKED",
                    "This wallet is parked. Reactivate it to send or convert from it.");
        }
        if (w.getBalanceMinor() < minor) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "You do not have enough money, including charges.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFrozen(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .map(a -> a.getStatus() == AccountStatus.FROZEN).orElse(false);
    }

    /** Every wallet, keyed "<account_number>:<currency>", so reconciliation sees USD, EUR and GBP wallets too. */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> balances() {
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : wallets.allBalances()) {
            result.put(LedgerApi.walletKey((String) row[0], (String) row[1]), ((Number) row[2]).longValue());
        }
        return result;
    }

    // ---------- helpers ----------

    /** 10 digits starting with 2. System accounts start with 9, so they can never collide. */
    private String newAccountNumber() {
        for (int i = 0; i < 10; i++) {
            long n = 2_000_000_000L + (long) (random.nextDouble() * 999_999_999L);
            String candidate = Long.toString(n);
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw ApiException.conflict("ACCOUNT_NUMBER_UNAVAILABLE", "Could not create an account number. Try again.");
    }

    static AccountView view(Account a) {
        return new AccountView(a.getAccountNumber(), a.getCustomerId(), a.getBalanceKobo(),
                a.getType(), a.getStatus(), a.getCreatedAt());
    }
}