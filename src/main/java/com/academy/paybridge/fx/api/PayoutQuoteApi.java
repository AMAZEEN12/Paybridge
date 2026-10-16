package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** How the remittance module stores and claims payout quotes without touching fx internals. */
public interface PayoutQuoteApi {

    /** Everything a payout quote locks. The fx module adds the id, status and the expiry. */
    record NewPayoutQuote(Long customerId, String sourceCurrency, String payoutCurrency,
                          long sourceAmountMinor, long feeMinor, long netMinor, long payoutAmountMinor,
                          BigDecimal rate, long usdEquivalentMinor,
                          Instant capExpiresAt,                    // PROVIDER_LOCK only: the partner's own expiry
                          String accountNumber, String accountName, String bankCode, String country,
                          String bankFieldsJson, String recipientType, String purpose,
                          String verificationSource, String rateMode, String providerRateRef,
                          Instant providerRateExpiresAt) {
    }

    record PayoutQuoteView(UUID id, Long customerId, String sourceCurrency, String payoutCurrency,
                           long sourceAmountMinor, long feeMinor, long netMinor, long payoutAmountMinor,
                           BigDecimal rate, long usdEquivalentMinor, String status, Instant expiresAt,
                           String accountNumber, String accountName, String bankCode, String country,
                           String recipientType, String purpose, String verificationSource,
                           String rateMode, String providerRateRef) {
    }

    /** Saves the quote and its destination. Expiry is 30 minutes, or the partner's if that is sooner. */
    PayoutQuoteView create(NewPayoutQuote request);

    Optional<PayoutQuoteView> find(UUID quoteId);

    /** ACTIVE to EXECUTING once, for the owner, while unexpired. Call inside the reserve transaction. */
    PayoutQuoteView claim(UUID quoteId, Long customerId);

    /** Ends the quote COMPLETED in the caller's transaction. */
    void complete(UUID quoteId, String journalKey);

    /** Ends the quote FAILED in the caller's transaction. */
    void fail(UUID quoteId, String reason);
}