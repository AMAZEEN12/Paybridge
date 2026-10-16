package com.academy.paybridge.fx.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitApi;
import com.academy.paybridge.compliance.api.TransferLimitContext;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.fx.api.RateApi;
import com.academy.paybridge.fx.api.TransferQuoteApi;
import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteDestination;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.domain.QuoteType;
import com.academy.paybridge.fx.repository.QuoteDestinationRepository;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Quotes for cross-currency transfers between PayBridge accounts (R-XFR-5 to R-XFR-8). Sits beside QuoteService and
 * reuses QuoteMath, so the fee and the rounding are exactly the conversion rules.
 */
@Service
public class TransferQuoteService implements TransferQuoteApi {

    private static final Logger log = LoggerFactory.getLogger(TransferQuoteService.class);

    private final QuoteRepository quotes;
    private final QuoteDestinationRepository destinations;
    private final RateApi rateApi;
    private final CurrencyRegistry currencies;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final TransferLimitApi limits;
    private final AuditService audit;
    private final QuoteProperties quoteProps;
    private final FeeProperties feeProps;
    private final Clock clock;
    private final TransactionTemplate tx;

    public TransferQuoteService(QuoteRepository quotes, QuoteDestinationRepository destinations, RateApi rateApi,
                                CurrencyRegistry currencies, CustomerApi customerApi, AccountApi accountApi,
                                TransferLimitApi limits, AuditService audit, QuoteProperties quoteProps,
                                FeeProperties feeProps, Clock clock, PlatformTransactionManager txManager) {
        this.quotes = quotes;
        this.destinations = destinations;
        this.rateApi = rateApi;
        this.currencies = currencies;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.limits = limits;
        this.audit = audit;
        this.quoteProps = quoteProps;
        this.feeProps = feeProps;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** NOT transactional on purpose: the rate lookup may make an HTTP call. Only the save is transactional. */
    @Override
    public TransferQuoteView create(NewTransferQuote r) {
        String source = normalise(r.sourceCurrency());
        String receive = normalise(r.receiveCurrency());
        if (source == null || receive == null || source.equals(receive)) {
            throw ApiException.badRequest("INVALID_CURRENCY",
                    "The currency you send and the currency they receive must be different. "
                            + "For the same currency, send a normal transfer.");
        }
        CurrencyInfo sourceInfo = currencies.require(source);
        CurrencyInfo receiveInfo = currencies.require(receive);
        long sourceMinor = currencies.parse(r.amount(), source).minor();

        if (r.senderAccount().equals(r.recipientAccount())) {
            throw ApiException.unprocessable("SAME_ACCOUNT",
                    "That is your own account. To move your own money between currencies, use a conversion.");
        }

        // R-XFR-2: the recipient needs a wallet in the currency (active or parked). Unknown number: ACCOUNT_NOT_FOUND.
        List<String> accepted = accountApi.receivableCurrencies(r.recipientAccount());
        if (!accepted.contains(receive)) {
            throw ApiException.unprocessable("RECIPIENT_CANNOT_RECEIVE",
                    "That account cannot receive " + receive + ". It accepts: " + String.join(", ", accepted) + ".");
        }
        Long ownerId = accountApi.ownerOf(r.recipientAccount()).orElseThrow(
                () -> ApiException.notFound("ACCOUNT_NOT_FOUND", "We could not find that account."));
        String recipientName = customerApi.get(ownerId).fullName();

        if (accountApi.isFrozen(r.senderAccount())) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to send money.");
        }
        WalletView wallet = accountApi.findWallet(r.senderAccount(), source).orElseThrow(
                () -> ApiException.notFound("WALLET_NOT_FOUND", "You do not have a " + source + " wallet."));
        if ("PARKED".equals(wallet.status())) {
            throw ApiException.unprocessable("WALLET_PARKED",
                    "This wallet is parked. Reactivate it to send or convert from it.");
        }
        if (wallet.balanceMinor() < sourceMinor) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "You do not have enough money in your " + source + " wallet.");
        }

        Instant now = clock.instant();
        if (quotes.countActiveQuotes(r.customerId(), now) >= quoteProps.maxActivePerCustomer()) {
            throw ApiException.unprocessable("TOO_MANY_ACTIVE_QUOTES",
                    "You have too many active quotes. Wait for them to expire or confirm an existing quote.");
        }

        // R-XFR-8: one snapshot gives the rate and the USD rate; no database transaction is open here.
        RateApi.RateWithUsd rates = rateApi.rateWithUsd(source, receive);

        CustomerView customer = customerApi.get(r.customerId());
        boolean promo = customer.createdAt() != null
                && Duration.between(customer.createdAt(), now).toDays() < feeProps.freeDays();

        QuoteMath.QuoteCalculation calc = QuoteMath.calculate(sourceMinor, sourceInfo.minorUnits(),
                receiveInfo.minorUnits(), rates.rate(), rates.toUsd(), feeProps.rate(), promo);

        // R-LIM2-2: checked now, and again at confirm.
        FraudResult verdict = limits.evaluate(new TransferLimitContext(
                TransferLimitContext.Kind.TRANSFER, calc.usdEquivalentMinor(), r.usedTodayUsdMinor()));
        if (verdict.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", verdict.joinedReasons());
        }

        UUID id = UUID.randomUUID();
        Quote quote = new Quote(id, r.customerId(), QuoteType.TRANSFER, source, receive, sourceMinor,
                calc.feeMinor(), calc.feeReason(), calc.netMinor(), calc.targetMinor(), rates.rate(),
                rates.snapshot().id(), calc.usdEquivalentMinor(), QuoteStatus.ACTIVE,
                now.plus(quoteProps.transferTtl()), now);
        QuoteDestination destination = QuoteDestination.paybridgeAccount(id, r.recipientAccount(), recipientName, receive);

        tx.execute(status -> {
            quotes.save(quote);
            destinations.save(destination);
            audit.record("customer:" + r.customerId(), "QUOTE_CREATED", id.toString(),
                    "transfer quote " + id + ": " + sourceMinor + " " + source + " to " + receive
                            + " at " + rates.rate() + " (fee " + calc.feeMinor() + " " + calc.feeReason() + ")");
            return null;
        });
        return view(quote, destination);
    }

    @Override
    public TransferQuoteView claim(UUID quoteId, Long customerId) {
        Quote existing = quotes.findById(quoteId)
                .filter(q -> q.getCustomerId().equals(customerId) && q.getType() == QuoteType.TRANSFER)
                .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found."));

        if (quotes.moveTransferToExecuting(quoteId, customerId, clock.instant()) == 0) {
            throw whyNotConfirmable(quoteId, existing);
        }
        Quote claimed = quotes.findById(quoteId).orElseThrow();
        QuoteDestination destination = destinations.findById(quoteId).orElseThrow();
        return view(claimed, destination);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID quoteId, String journalKey) {
        Quote quote = quotes.findById(quoteId).orElseThrow();
        quote.setStatus(QuoteStatus.COMPLETED);
        quote.setCompletedAt(clock.instant());
        quote.setJournalKey(journalKey);
        quotes.save(quote);
    }

    @Override
    public void fail(UUID quoteId, Long customerId, String reason) {
        try {
            String shortReason = reason == null ? "UNKNOWN" : (reason.length() > 200 ? reason.substring(0, 200) : reason);
            quotes.markFailed(quoteId, shortReason);
            audit.recordIndependently("customer:" + customerId, "TRANSFER_QUOTE_FAILED", quoteId.toString(),
                    "transfer quote " + quoteId + " failed: " + shortReason);
        } catch (RuntimeException e) {
            // never hide the original error; the clean-up job ends a quote that is still EXECUTING
            log.error("Could not mark transfer quote {} as FAILED: {}", quoteId, e.getClass().getSimpleName());
        }
    }

    private ApiException whyNotConfirmable(UUID quoteId, Quote before) {
        Quote now = quotes.findById(quoteId).orElse(before);
        QuoteStatus status = now.getStatus();
        if (status == QuoteStatus.COMPLETED || status == QuoteStatus.EXECUTING) {
            return ApiException.unprocessable("QUOTE_ALREADY_USED", "This quote has already been used.");
        }
        if (status == QuoteStatus.FAILED) {
            return ApiException.unprocessable("QUOTE_FAILED", "This quote has failed and cannot be reused.");
        }
        return ApiException.unprocessable("QUOTE_EXPIRED", "This quote has expired. Please request a new quote.");
    }

    private static TransferQuoteView view(Quote q, QuoteDestination d) {
        return new TransferQuoteView(q.getId(), q.getCustomerId(), d.getAccountNumber(), d.getAccountName(),
                q.getSourceCurrency(), q.getTargetCurrency(), q.getSourceAmountMinor(), q.getFeeMinor(),
                q.getFeeReason().name(), q.getNetMinor(), q.getTargetAmountMinor(), q.getRate(),
                q.getUsdEquivalentMinor(), q.getStatus().name(), q.getExpiresAt());
    }

    private static String normalise(String currency) {
        return currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
    }
}