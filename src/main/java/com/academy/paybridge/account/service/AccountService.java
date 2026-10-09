package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountStatusChangedEvent;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

@Service
public class AccountService implements AccountApi {

    private static final int MAX_ACCOUNTS_PER_CUSTOMER = 5;

    private final AccountRepository accounts;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accounts, AuditService audit, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.audit = audit;
        this.events = events;
    }

    // ---------- used by the account controller ----------

    @Transactional
    public AccountView open(Long customerId) {
        if (accounts.countByCustomerIdAndType(customerId, AccountType.CUSTOMER) >= MAX_ACCOUNTS_PER_CUSTOMER) {
            throw ApiException.unprocessable("ACCOUNT_LIMIT_REACHED",
                    "You can open at most " + MAX_ACCOUNTS_PER_CUSTOMER + " accounts.");
        }
        String number = newAccountNumber();
        Account saved = accounts.saveAndFlush(new Account(number, customerId));
        audit.record("customer:" + customerId, "ACCOUNT_OPENED", null, "account " + Money.maskAccount(number));
        return view(saved);
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

    /** Development only: money from nowhere. The controller that calls this is switched off in production. */
    @Transactional
    public AccountView devFund(String accountNumber, Long customerId, long kobo) {
        getOwned(accountNumber, customerId);
        credit(accountNumber, kobo);
        audit.record("customer:" + customerId, "DEV_FUNDING", null,
                "account " + Money.maskAccount(accountNumber) + " +" + kobo + " kobo");
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInOrder(Collection<String> accountNumbers) {
        for (String number : new TreeSet<>(accountNumbers)) {          // sorted, no duplicates
            accounts.findForUpdate(number)
                    .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(String accountNumber, long kobo) {
        Account a = accounts.findForUpdate(accountNumber)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        if (a.getStatus() == AccountStatus.FROZEN) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to send money.");
        }
        if (a.getBalanceKobo() < kobo) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "You do not have enough money, including charges.");
        }
        a.setBalanceKobo(a.getBalanceKobo() - kobo);
        accounts.save(a);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(String accountNumber, long kobo) {
        Account a = accounts.findForUpdate(accountNumber)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        a.setBalanceKobo(Math.addExact(a.getBalanceKobo(), kobo));
        accounts.save(a);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFrozen(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .map(a -> a.getStatus() == AccountStatus.FROZEN).orElse(false);
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
