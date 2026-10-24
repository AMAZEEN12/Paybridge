package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.FundingDetails;
import com.academy.paybridge.account.domain.Wallet;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.repository.FundingDetailsRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Issues (once) and returns the account details of a wallet. The account row lock keeps two requests from both issuing. */
@Service
public class FundingDetailsService {

    private final FundingDetailsRepository details;
    private final AccountRepository accounts;
    private final FundingDetailsGenerator generator;
    private final AuditService audit;

    public FundingDetailsService(FundingDetailsRepository details, AccountRepository accounts,
                                 FundingDetailsGenerator generator, AuditService audit) {
        this.details = details;
        this.accounts = accounts;
        this.generator = generator;
        this.audit = audit;
    }

    /** For the wallet screen: the details of one of my wallets, issued now if this is the first time. */
    @Transactional
    public FundingDetails forWallet(Long customerId, String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);

        Account first = accounts.findByCustomerIdOrderByIdAsc(customerId).stream()
                .filter(a -> a.getType() == AccountType.CUSTOMER)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        Account account = accounts.findForUpdate(first.getAccountNumber())
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));

        Wallet wallet = account.getWallets().stream()
                .filter(w -> w.getCurrency().equals(code))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "You do not have a " + code + " wallet."));

        return ensure(account, wallet);
    }

    /** Joins the caller's transaction. WalletService calls this right after it creates a wallet. */
    @Transactional
    public FundingDetails ensure(Account account, Wallet wallet) {
        return details.findByWalletId(wallet.getId()).orElseGet(() -> {
            FundingDetailsGenerator.Generated g = generator.generate(wallet.getCurrency(), account.getAccountNumber());
            FundingDetails saved = details.save(new FundingDetails(wallet, g.bankName(), g.bankCountry(),
                    g.accountNumber(), g.routingLabel(), g.routingValue(), g.iban(), g.swiftBic(),
                    generator.reference()));
            audit.record("customer:" + account.getCustomerId(), "FUNDING_DETAILS_ISSUED", null,
                    "account " + Money.maskAccount(account.getAccountNumber()) + ", " + wallet.getCurrency());
            return saved;
        });
    }
}