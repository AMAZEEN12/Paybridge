package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The only door the transfer module uses for cross-currency transfer quotes (R-XFR-5 to R-XFR-8). */
public interface TransferQuoteApi {

    /** What the sender asked for. {@code usedTodayUsdMinor} is the sender's non-NGN total for the Lagos day so far. */
    record NewTransferQuote(Long customerId, String senderAccount, String recipientAccount,
                            String sourceCurrency, String receiveCurrency, BigDecimal amount,
                            long usedTodayUsdMinor) {
    }

    /** A locked transfer quote. Amounts are minor units: {@code netMinor} = source - fee, {@code receiveAmountMinor} is exact. */
    record TransferQuoteView(UUID id, Long customerId, String recipientAccount, String recipientName,
                             String sourceCurrency, String receiveCurrency,
                             long sourceAmountMinor, long feeMinor, String feeReason, long netMinor,
                             long receiveAmountMinor, BigDecimal rate, long usdEquivalentMinor,
                             String status, Instant expiresAt) {
    }

    /**
     * Issues a TRANSFER quote locked for the transfer TTL (30 minutes). Reads the rate BEFORE any transaction opens
     * (R-XFR-8): call it with no transaction open. Refuses with the rates message when rates are stale.
     */
    TransferQuoteView create(NewTransferQuote request);

    /**
     * Moves the caller's own unexpired ACTIVE TRANSFER quote to EXECUTING, once (R-XFR-7). Commits on its own.
     * Anything else (not theirs, not a transfer quote, used, failed, expired) is refused.
     */
    TransferQuoteView claim(UUID quoteId, Long customerId);

    /** Ends the quote as COMPLETED in the caller's transaction, so it commits with the money movement. */
    void complete(UUID quoteId, String journalKey);

    /** Ends an EXECUTING quote as FAILED in its own transaction (survives the rollback). Never throws. */
    void fail(UUID quoteId, Long customerId, String reason);
}