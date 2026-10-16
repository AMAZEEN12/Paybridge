package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitApi;
import com.academy.paybridge.compliance.api.TransferLimitContext;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.fx.api.TransferQuoteApi;
import com.academy.paybridge.fx.api.TransferQuoteApi.NewTransferQuote;
import com.academy.paybridge.fx.api.TransferQuoteApi.TransferQuoteView;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.transfer.api.TransferEvent;
import com.academy.paybridge.transfer.api.TransferStage;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-currency transfers between PayBridge accounts (R-XFR-5 to R-XFR-10, R-NTF-2). The quote lives in the fx
 * module; this class lets the customer ask for one and confirms it in a single database transaction.
 */
@Service
public class CrossCurrencyTransferService {

    private static final Logger log = LoggerFactory.getLogger(CrossCurrencyTransferService.class);

    private final TransferQuoteApi quotes;
    private final AccountApi accounts;
    private final CustomerApi customers;
    private final TransferRepository transfers;
    private final TransferLimitApi limits;
    private final LedgerApi ledger;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ResolveRateLimiter limiter;
    private final CurrencyRegistry currencies;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public CrossCurrencyTransferService(TransferQuoteApi quotes, AccountApi accounts, CustomerApi customers,
                                        TransferRepository transfers, TransferLimitApi limits, LedgerApi ledger,
                                        AuditService audit, ApplicationEventPublisher events,
                                        ResolveRateLimiter limiter, CurrencyRegistry currencies,
                                        PlatformTransactionManager txManager, Clock clock, AppProperties appProps) {
        this.quotes = quotes;
        this.accounts = accounts;
        this.customers = customers;
        this.transfers = transfers;
        this.limits = limits;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
        this.limiter = limiter;
        this.currencies = currencies;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.zone = ZoneId.of(appProps.zone() == null ? "Africa/Lagos" : appProps.zone());
    }

    // =====================================================================================
    // QUOTE (changes nothing; no notification, R-NTF-3)
    // =====================================================================================

    public TransferQuoteView quote(Long customerId, String recipientAccount, String sourceCurrency,
                                   String receiveCurrency, BigDecimal amount) {
        limiter.check(customerId);                 // a quote reveals the recipient's name, so it shares the lookup limit
        String sender = accounts.existingFor(customerId).accountNumber();
        return quotes.create(new NewTransferQuote(customerId, sender, recipientAccount, sourceCurrency,
                receiveCurrency, amount, usedTodayUsd(sender)));
    }

    // =====================================================================================
    // CONFIRM
    // =====================================================================================

    public TransferResult confirm(Long customerId, UUID quoteId, String pin, String key) {
        String actor = "customer:" + customerId;
        String sender = accounts.existingFor(customerId).accountNumber();
        String hash = RequestHasher.hash("CROSS", sender, quoteId.toString(), null, 0L, null);

        // A retry with the same key and the same quote returns the first answer, even though the quote is now used.
        Optional<TransferResult> replay = replayIfSeen(sender, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        customers.verifyPin(customerId, pin);            // before the claim: a wrong PIN does not burn the quote

        TransferQuoteView quote = quotes.claim(quoteId, customerId);   // single use, owner only (R-XFR-7)

        try {
            return tx.execute(status -> post(actor, sender, quote, key, hash));
        } catch (RuntimeException e) {
            String reason = e instanceof ApiException api ? api.getCode() : e.getClass().getSimpleName();
            quotes.fail(quoteId, customerId, reason);
            audit.recordIndependently(actor, "TRANSFER_REJECTED", quoteId.toString(), reason);
            publishNotSent(sender, quote);
            throw e;
        }
    }

    /** Runs in one transaction. Anything that throws rolls back every balance and ledger line. */
    private TransferResult post(String actor, String sender, TransferQuoteView q, String key, String hash) {
        String recipient = q.recipientAccount();
        String from = q.sourceCurrency();
        String to = q.receiveCurrency();

        lockInOrder(sender, from, recipient, to);

        // Re-check at confirm time (R-XFR-7): the recipient can still receive, and the limits still hold.
        if (!accounts.receivableCurrencies(recipient).contains(to)) {
            throw ApiException.unprocessable("RECIPIENT_CANNOT_RECEIVE", "That account can no longer receive " + to + ".");
        }
        FraudResult verdict = limits.evaluate(new TransferLimitContext(
                TransferLimitContext.Kind.TRANSFER, q.usdEquivalentMinor(), usedTodayUsd(sender)));
        if (verdict.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", verdict.joinedReasons());
        }

        // Frozen account, parked wallet and too little money are refused by debit().
        accounts.debit(sender, from, q.sourceAmountMinor());
        accounts.credit(recipient, to, q.receiveAmountMinor());

        String reference = "trf_" + UUID.randomUUID().toString().replace("-", "");
        String journalKey = "XFER:" + q.id();

        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(LedgerApi.walletKey(sender, from), JournalLine.Side.DEBIT, from, q.sourceAmountMinor()));
        lines.add(new JournalLine("EXT:FX_POOL:" + from, JournalLine.Side.CREDIT, from, q.netMinor()));
        if (q.feeMinor() > 0) {
            lines.add(new JournalLine("EXT:FX_FEE:" + from, JournalLine.Side.CREDIT, from, q.feeMinor()));
        }
        lines.add(new JournalLine("EXT:FX_POOL:" + to, JournalLine.Side.DEBIT, to, q.receiveAmountMinor()));
        lines.add(new JournalLine(LedgerApi.walletKey(recipient, to), JournalLine.Side.CREDIT, to, q.receiveAmountMinor()));
        ledger.recordJournal(journalKey, reference, "TRANSFER", lines);

        // amount + fee = what the sender paid (the fee comes out of the amount they typed, R-XFR-6).
        Transfer t = new Transfer(reference, TransferType.INTERNAL, TransferStatus.SUCCESSFUL, sender, recipient,
                from, q.netMinor(), q.feeMinor(), 0L, 0L, null, key, hash, clock.instant());
        t.setCrossCurrency(to, q.receiveAmountMinor(), q.id());
        t.setUsdEquivalentMinor(q.usdEquivalentMinor());
        transfers.save(t);

        quotes.complete(q.id(), journalKey);

        audit.record(actor, "TRANSFER_COMPLETED", reference,
                "cross-currency " + currencies.format(q.sourceAmountMinor(), from)
                        + " to " + currencies.format(q.receiveAmountMinor(), to)
                        + " (rate " + q.rate() + ", fee " + q.feeMinor() + " " + q.feeReason() + ", quote " + q.id() + ")");

        events.publishEvent(new TransferEvent(reference, TransferType.INTERNAL, TransferStage.COMPLETED, sender,
                recipient, null, from, q.netMinor(), q.feeMinor(), q.sourceAmountMinor(), false,
                to, q.receiveAmountMinor()));

        return new TransferResult(t, false);
    }

    // =====================================================================================
    // HELPERS
    // =====================================================================================

    /**
     * Locks the two wallet rows in ONE order for everybody: by account number. A to B and B to A at the same moment
     * therefore both lock the lower account first, so they queue instead of deadlocking.
     */
    private void lockInOrder(String senderAccount, String senderCurrency, String recipientAccount,
                             String recipientCurrency) {
        if (senderAccount.compareTo(recipientAccount) <= 0) {
            accounts.lockWalletsInOrder(List.of(senderAccount), senderCurrency);
            accounts.lockWalletsInOrder(List.of(recipientAccount), recipientCurrency);
        } else {
            accounts.lockWalletsInOrder(List.of(recipientAccount), recipientCurrency);
            accounts.lockWalletsInOrder(List.of(senderAccount), senderCurrency);
        }
    }

    private long usedTodayUsd(String sender) {
        return transfers.sumUsdSince(sender, LagosDay.startOf(clock.instant(), zone), TransferStatus.FAILED);
    }

    private Optional<TransferResult> replayIfSeen(String source, String key, String hash) {
        Optional<Transfer> existing = transfers.findBySourceAccountNumberAndIdempotencyKey(source, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!existing.get().getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request. Use a new key.");
        }
        return Optional.of(new TransferResult(existing.get(), true));
    }

    /** R-NTF-2: one notification for the sender, published after the rollback, so it is delivered at once. */
    private void publishNotSent(String sender, TransferQuoteView q) {
        try {
            events.publishEvent(new TransferEvent(q.id().toString(), TransferType.INTERNAL, TransferStage.NOT_SENT,
                    sender, q.recipientAccount(), null, q.sourceCurrency(), q.netMinor(), q.feeMinor(),
                    q.sourceAmountMinor(), false, q.receiveCurrency(), q.receiveAmountMinor()));
        } catch (RuntimeException e) {
            log.warn("Could not publish the NOT_SENT event: {}", e.getClass().getSimpleName());
        }
    }
}