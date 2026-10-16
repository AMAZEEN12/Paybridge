package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.PayoutQuoteApi;
import com.academy.paybridge.fx.api.QuoteTotalsApi;
import com.academy.paybridge.fx.domain.FeeReason;
import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteDestination;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.domain.QuoteType;
import com.academy.paybridge.fx.repository.QuoteDestinationRepository;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Payout quotes in the fx tables, behind two small APIs (step B3). Business rules stay in the remittance module. */
@Service
public class PayoutQuoteStore implements PayoutQuoteApi, QuoteTotalsApi {

    private final QuoteRepository quotes;
    private final QuoteDestinationRepository destinations;
    private final QuoteProperties quoteProps;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PayoutQuoteStore(QuoteRepository quotes, QuoteDestinationRepository destinations,
                            QuoteProperties quoteProps, AuditService audit, Clock clock,
                            PlatformTransactionManager txManager) {
        this.quotes = quotes;
        this.destinations = destinations;
        this.quoteProps = quoteProps;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public PayoutQuoteView create(NewPayoutQuote r) {
        Instant now = clock.instant();
        if (quotes.countActiveQuotes(r.customerId(), now) >= quoteProps.maxActivePerCustomer()) {
            throw ApiException.unprocessable("TOO_MANY_ACTIVE_QUOTES",
                    "You have too many active quotes. Wait for one to expire or use an existing quote.");
        }
        Instant expires = now.plus(quoteProps.transferTtl());                 // 30 minutes (R-PAY-3)
        if (r.capExpiresAt() != null && r.capExpiresAt().isBefore(expires)) {
            expires = r.capExpiresAt();
        }
        if (!expires.isAfter(now)) {
            throw ApiException.unprocessable("PAYOUT_RATE_UNAVAILABLE",
                    "The payout rate has already expired. Please try again.");
        }

        UUID id = UUID.randomUUID();
        Quote quote = new Quote(id, r.customerId(), QuoteType.PAYOUT, r.sourceCurrency(), r.payoutCurrency(),
                r.sourceAmountMinor(), r.feeMinor(), FeeReason.STANDARD, r.netMinor(), r.payoutAmountMinor(),
                r.rate().setScale(12, RoundingMode.HALF_EVEN), null, r.usdEquivalentMinor(),
                QuoteStatus.ACTIVE, expires, now);
        QuoteDestination destination = QuoteDestination.bankAccount(id, r.accountNumber(), r.accountName(),
                r.payoutCurrency(), r.bankCode(), null, r.country(), r.bankFieldsJson(), r.recipientType(),
                r.purpose(), r.verificationSource(), r.rateMode(), r.providerRateRef(), r.providerRateExpiresAt());

        tx.execute(status -> {
            quotes.save(quote);
            destinations.save(destination);
            audit.record("customer:" + r.customerId(), "QUOTE_CREATED", id.toString(),
                    "payout quote " + id + ": " + r.sourceAmountMinor() + " " + r.sourceCurrency()
                            + " to " + r.payoutCurrency() + " in " + r.country());
            return null;
        });
        return view(quote, destination);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayoutQuoteView> find(UUID quoteId) {
        return quotes.findById(quoteId)
                .filter(q -> q.getType() == QuoteType.PAYOUT)
                .flatMap(q -> destinations.findById(quoteId).map(d -> view(q, d)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PayoutQuoteView claim(UUID quoteId, Long customerId) {
        Quote existing = quotes.findById(quoteId)
                .filter(q -> q.getCustomerId().equals(customerId) && q.getType() == QuoteType.PAYOUT)
                .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found."));

        if (quotes.movePayoutToExecuting(quoteId, customerId, clock.instant()) == 0) {
            QuoteStatus now = quotes.findById(quoteId).orElse(existing).getStatus();
            if (now == QuoteStatus.COMPLETED || now == QuoteStatus.EXECUTING) {
                throw ApiException.unprocessable("QUOTE_ALREADY_USED", "This quote has already been used.");
            }
            if (now == QuoteStatus.FAILED) {
                throw ApiException.unprocessable("QUOTE_FAILED", "This quote has failed and cannot be reused.");
            }
            throw ApiException.unprocessable("QUOTE_EXPIRED", "This quote has expired. Please request a new quote.");
        }
        Quote claimed = quotes.findById(quoteId).orElseThrow();
        return view(claimed, destinations.findById(quoteId).orElseThrow());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID quoteId, String journalKey) {
        Quote q = quotes.findById(quoteId).orElseThrow();
        q.setStatus(QuoteStatus.COMPLETED);
        q.setCompletedAt(clock.instant());
        q.setJournalKey(journalKey);
        quotes.save(q);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(UUID quoteId, String reason) {
        Quote q = quotes.findById(quoteId).orElseThrow();
        q.setStatus(QuoteStatus.FAILED);
        q.setFailureReason(reason);
        q.setCompletedAt(clock.instant());
        quotes.save(q);
    }

    @Override
    @Transactional(readOnly = true)
    public long payoutUsdSince(Long customerId, Instant since) {
        return quotes.sumPayoutUsdSince(customerId, since);
    }

    private static PayoutQuoteView view(Quote q, QuoteDestination d) {
        return new PayoutQuoteView(q.getId(), q.getCustomerId(), q.getSourceCurrency(), q.getTargetCurrency(),
                q.getSourceAmountMinor(), q.getFeeMinor(), q.getNetMinor(), q.getTargetAmountMinor(), q.getRate(),
                q.getUsdEquivalentMinor(), q.getStatus().name(), q.getExpiresAt(), d.getAccountNumber(),
                d.getAccountName(), d.getBankCode(), d.getCountry(), d.getRecipientType(), d.getPurpose(),
                d.getVerificationSource(), d.getRateMode(), d.getProviderRateRef());
    }
}