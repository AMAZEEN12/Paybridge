package com.academy.paybridge.fx.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.compliance.api.ConversionComplianceApi;
import com.academy.paybridge.compliance.api.ConversionContext;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Executes a currency conversion in one database transaction (R-CONV-4).
 * Locks the account, then wallets in sorted order, re-checks limits, balance, and status,
 * writes balanced ledger entries, and completes the quote.
 */
@Service
public class ConversionService {

    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final ConversionComplianceApi compliance;
    private final QuoteRepository quotes;
    private final AuditService audit;
    private final AppProperties appProps;
    private final Clock clock;

    public ConversionService(AccountApi accounts, LedgerApi ledger,
                             ConversionComplianceApi compliance, QuoteRepository quotes,
                             AuditService audit, AppProperties appProps, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.compliance = compliance;
        this.quotes = quotes;
        this.audit = audit;
        this.appProps = appProps;
        this.clock = clock;
    }

    @Transactional
    public void executeConversion(Quote quote, String accountNumber) {
        Instant now = clock.instant();

        // 1. Re-check limits at confirm time (R-LIM-3)
        ZoneId zone = ZoneId.of(appProps.zone() != null ? appProps.zone() : "Africa/Lagos");
        Instant startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        long dailyOutgoingUsd = quotes.sumOutgoingUsdMinorToday(quote.getCustomerId(), startOfDay);
        // Note: quote is already EXECUTING, so subtract its own usdEquivalentMinor from other total
        long dailyOther = Math.max(0, dailyOutgoingUsd - quote.getUsdEquivalentMinor());
        int recentCount = Math.max(0, quotes.countRecentConversions(quote.getCustomerId(),
                now.minus(Duration.ofMinutes(compliance.velocityWindowMinutes()))) - 1);

        ConversionContext ctx = new ConversionContext(quote.getCustomerId(),
                quote.getUsdEquivalentMinor(), dailyOther, recentCount);
        FraudResult fraud = compliance.evaluate(ctx);
        if (fraud.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", fraud.joinedReasons());
        }

        // 2. Lock account row, then both wallets in sorted order (R-CONV-4, concern C)
        accounts.lockForConversion(accountNumber, quote.getSourceCurrency(), quote.getTargetCurrency());

        // 3. Re-check source wallet balance (R-CONV-6)
        WalletView sourceWallet = accounts.findWallet(accountNumber, quote.getSourceCurrency())
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "Source wallet not found."));
        if (sourceWallet.balanceMinor() < quote.getSourceAmountMinor()) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "You do not have enough money in your " + quote.getSourceCurrency() + " wallet.");
        }

        // 4. Debit source wallet
        accounts.debit(accountNumber, quote.getSourceCurrency(), quote.getSourceAmountMinor());

        // 5. Credit target wallet
        accounts.credit(accountNumber, quote.getTargetCurrency(), quote.getTargetAmountMinor());

        // 6. Record balanced multi-currency ledger lines (spec 7.2)
        String journalKey = "CONV:" + quote.getId();
        ledger.recordConversion(quote.getId().toString(), accountNumber,
                quote.getSourceCurrency(), quote.getTargetCurrency(),
                quote.getSourceAmountMinor(), quote.getFeeMinor(), quote.getNetMinor(), quote.getTargetAmountMinor());

        // 7. Complete the quote
        quote.setStatus(QuoteStatus.COMPLETED);
        quote.setCompletedAt(now);
        quote.setJournalKey(journalKey);
        quotes.save(quote);

        // 8. Audit log (R-CONV-5)
        audit.record("customer:" + quote.getCustomerId(), "CONVERSION", quote.getId().toString(),
                "account " + Money.maskAccount(accountNumber) + ": converted "
                        + quote.getSourceAmountMinor() + " " + quote.getSourceCurrency() + " to "
                        + quote.getTargetAmountMinor() + " " + quote.getTargetCurrency()
                        + " (rate " + quote.getRate() + ", fee " + quote.getFeeMinor() + " " + quote.getFeeReason() + ")");
    }
}
