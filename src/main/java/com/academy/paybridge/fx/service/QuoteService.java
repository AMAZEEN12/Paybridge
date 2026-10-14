package com.academy.paybridge.fx.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.compliance.api.ConversionComplianceApi;
import com.academy.paybridge.compliance.api.ConversionContext;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.fx.api.RateApi;
import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.domain.QuoteType;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final QuoteRepository quotes;
    private final RateApi rateApi;
    private final CurrencyRegistry currencies;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final ConversionComplianceApi compliance;
    private final ConversionService conversionService;
    private final AuditService audit;
    private final QuoteProperties quoteProps;
    private final FeeProperties feeProps;
    private final AppProperties appProps;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public QuoteService(
            QuoteRepository quotes,
            RateApi rateApi,
            CurrencyRegistry currencies,
            CustomerApi customerApi,
            AccountApi accountApi,
            ConversionComplianceApi compliance,
            ConversionService conversionService,
            AuditService audit,
            QuoteProperties quoteProps,
            FeeProperties feeProps,
            AppProperties appProps,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.quotes = quotes;
        this.rateApi = rateApi;
        this.currencies = currencies;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.compliance = compliance;
        this.conversionService = conversionService;
        this.audit = audit;
        this.quoteProps = quoteProps;
        this.feeProps = feeProps;
        this.appProps = appProps;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Issues a locked quote for a conversion. NOT transactional on purpose: the rate lookup may make an HTTP call
     * (an on-demand refresh), and no database transaction may be open during it. Only the save is transactional.
     */
    public Quote createConversionQuote(Long customerId, String sourceCurrency, String targetCurrency,
                                       BigDecimal amount) {
        String source = normalise(sourceCurrency);
        String target = normalise(targetCurrency);

        if (source == null || target == null || source.equals(target)) {
            throw ApiException.badRequest("INVALID_CURRENCY", "Source and target currency must be different.");
        }

        CurrencyInfo sourceInfo = currencies.require(source);
        CurrencyInfo targetInfo = currencies.require(target);

        CurrencyAmount sourceAmount = currencies.parse(amount, source);
        long sourceMinor = sourceAmount.minor();

        CustomerView customer = customerApi.get(customerId);
        AccountView account = accountApi.existingFor(customerId);

        if (accountApi.isFrozen(account.accountNumber())) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to convert money.");
        }

        WalletView sourceWallet = accountApi.findWallet(account.accountNumber(), source)
                .orElseThrow(() -> ApiException.unprocessable("WALLET_NOT_FOUND",
                        "You do not have a " + source + " wallet."));

        WalletView targetWallet = accountApi.findWallet(account.accountNumber(), target)
                .orElseThrow(() -> ApiException.unprocessable("WALLET_NOT_FOUND",
                        "You do not have a " + target + " wallet."));

        if ("PARKED".equals(sourceWallet.status())) {
            throw ApiException.unprocessable("WALLET_PARKED",
                    "This wallet is parked. Reactivate it to send or convert from it.");
        }
        if ("PARKED".equals(targetWallet.status())) {
            throw ApiException.unprocessable("WALLET_PARKED",
                    "Target wallet is parked. Reactivate it before converting into it.");
        }
        if (sourceWallet.balanceMinor() < sourceMinor) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "You do not have enough money in your " + source + " wallet.");
        }

        Instant now = clock.instant();

        long activeCount = quotes.countActiveQuotes(customerId, now);
        if (activeCount >= quoteProps.maxActivePerCustomer()) {
            throw ApiException.unprocessable("TOO_MANY_ACTIVE_QUOTES",
                    "You have too many active quotes. Wait for them to expire or confirm an existing quote.");
        }

        // One snapshot gives both the conversion rate and the USD rate (no database transaction is open here).
        RateApi.RateWithUsd rates = rateApi.rateWithUsd(source, target);

        boolean isPromo = false;
        if (customer.createdAt() != null) {
            long days = Duration.between(customer.createdAt(), now).toDays();
            isPromo = days < feeProps.freeDays();
        }

        QuoteMath.QuoteCalculation calc = QuoteMath.calculate(
                sourceMinor,
                sourceInfo.minorUnits(),
                targetInfo.minorUnits(),
                rates.rate(),
                rates.toUsd(),
                feeProps.rate(),
                isPromo
        );

        ZoneId zone = ZoneId.of(appProps.zone() != null ? appProps.zone() : "Africa/Lagos");
        Instant startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();

        long dailyOutgoingUsd = quotes.sumOutgoingUsdMinorToday(customerId, startOfDay);
        int recentCount = quotes.countRecentConversions(customerId,
                now.minus(Duration.ofMinutes(compliance.velocityWindowMinutes())));

        ConversionContext ctx = new ConversionContext(customerId, calc.usdEquivalentMinor(), dailyOutgoingUsd,
                recentCount);
        FraudResult fraud = compliance.evaluate(ctx);
        if (fraud.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", fraud.joinedReasons());
        }

        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = now.plus(quoteProps.conversionTtl());

        Quote quote = new Quote(quoteId, customerId, QuoteType.CONVERSION, source, target, sourceMinor,
                calc.feeMinor(), calc.feeReason(), calc.netMinor(), calc.targetMinor(), rates.rate(),
                rates.snapshot().id(), calc.usdEquivalentMinor(), QuoteStatus.ACTIVE, expiresAt, now);

        return transactionTemplate.execute(status -> {
            Quote saved = quotes.save(quote);
            audit.record("customer:" + customerId, "QUOTE_CREATED", quoteId.toString(),
                    "quote " + quoteId + ": convert " + sourceMinor + " " + source + " to " + target
                            + " at " + rates.rate() + " (fee " + calc.feeMinor() + " " + calc.feeReason() + ")");
            return saved;
        });
    }

    /**
     * Confirms a quote and runs the conversion. The conditional update makes the quote single-use and owner-only.
     * Once the quote is EXECUTING, ANY failure ends it as FAILED, whichever step threw.
     */
    public Quote confirm(UUID quoteId, Long customerId) {
        Instant now = clock.instant();

        int moved = quotes.moveToExecuting(quoteId, customerId, now);
        if (moved == 0) {
            throw whyNotConfirmable(quoteId, customerId);
        }

        try {
            Quote quote = quotes.findById(quoteId)
                    .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found."));
            AccountView account = accountApi.existingFor(customerId);
            conversionService.executeConversion(quote, account.accountNumber());
            return quotes.findById(quoteId).orElseThrow();
        } catch (RuntimeException e) {
            failExecuting(quoteId, customerId, e);
            throw e;
        }
    }

    /** Why a confirm was refused. A quote that is not the caller's looks exactly like one that does not exist. */
    private ApiException whyNotConfirmable(UUID quoteId, Long customerId) {
        Quote existing = quotes.findById(quoteId)
                .filter(q -> q.getCustomerId().equals(customerId))
                .orElse(null);
        if (existing == null) {
            return ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found.");
        }
        QuoteStatus status = existing.getStatus();
        if (status == QuoteStatus.COMPLETED || status == QuoteStatus.EXECUTING) {
            return ApiException.unprocessable("QUOTE_ALREADY_USED", "This quote has already been used.");
        }
        if (status == QuoteStatus.FAILED) {
            return ApiException.unprocessable("QUOTE_FAILED", "This quote has failed and cannot be reused.");
        }
        // EXPIRED, or ACTIVE but past its time (the clean-up job has not run yet)
        return ApiException.unprocessable("QUOTE_EXPIRED", "This quote has expired. Please request a new quote.");
    }

    private void failExecuting(UUID quoteId, Long customerId, RuntimeException cause) {
        String reason = cause instanceof ApiException api ? api.getCode() : cause.getClass().getSimpleName();
        if (cause instanceof ApiException) {
            log.warn("Conversion refused for quote {}: {}", quoteId, reason);
        } else {
            log.error("Conversion failed for quote {}", quoteId, cause);
        }
        try {
            quotes.markFailed(quoteId, reason);
            audit.recordIndependently("customer:" + customerId, "CONVERSION_FAILED", quoteId.toString(),
                    "quote " + quoteId + " failed: " + reason);
        } catch (RuntimeException e) {
            // never hide the original error; the clean-up job will end this quote if it is still EXECUTING
            log.error("Could not mark quote {} as FAILED: {}", quoteId, e.getClass().getSimpleName());
        }
    }

    /** Called by QuoteCleanupJob. Two conditional updates, so it can never overwrite a COMPLETED quote. */
    public void cleanupQuotes() {
        Instant now = clock.instant();

        int expired = quotes.expireOverdue(now);
        int stuck = quotes.failStuck(now.minus(quoteProps.stuckExecutingLimit()), "STUCK_EXECUTING");

        if (expired > 0) {
            log.info("{} quote(s) expired", expired);
        }
        if (stuck > 0) {
            log.warn("{} quote(s) were stuck in EXECUTING and have been failed", stuck);
            audit.recordIndependently("system", "QUOTE_STUCK_FAILED", null,
                    stuck + " quote(s) stuck in EXECUTING were marked FAILED");
        }
    }

    private static String normalise(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}