package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.PayoutQuoteApi;
import com.academy.paybridge.fx.api.PayoutQuoteApi.PayoutQuoteView;
import com.academy.paybridge.ledger.api.JournalDraft;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PayoutJournals;
import com.academy.paybridge.remittance.client.PayoutGateway;
import com.academy.paybridge.remittance.client.PayoutRejectedException;
import com.academy.paybridge.remittance.client.PayoutRequest;
import com.academy.paybridge.remittance.client.PayoutResult;
import com.academy.paybridge.remittance.client.PayoutStatus;
import com.academy.paybridge.remittance.client.PayoutUnavailableException;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.repository.RemittanceAttemptRepository;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.transfer.api.TransferEvent;
import com.academy.paybridge.transfer.api.TransferStage;
import com.academy.paybridge.transfer.api.TransferType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sends a payout to the partner and settles its outcome (R-PAY-6 to R-PAY-10). Every settle step runs in a short
 * transaction under a row lock and only acts on a payout still PENDING or RETRYING, so the webhook, a verify and the
 * scheduled job can race and each outcome is applied once (R-PAY-8). No transaction is open during a partner call.
 *
 * A timeout is not a failure (R-PAY-9). A partner that has no record of our reference gets the request SENT AGAIN with
 * the same reference, which can never pay twice (R-PAY-7); it is never refunded just because it is unknown.
 */
@Service
public class PayoutSettlementService {

    private final RemittanceRepository remittances;
    private final RemittanceAttemptRepository attempts;
    private final PayoutQuoteApi quoteApi;
    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final PayoutGateway gateway;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayoutSettlementService(RemittanceRepository remittances, RemittanceAttemptRepository attempts,
                                   PayoutQuoteApi quoteApi, AccountApi accounts, LedgerApi ledger,
                                   PayoutGateway gateway, AuditService audit, ApplicationEventPublisher events,
                                   PlatformTransactionManager txManager, Clock clock) {
        this.remittances = remittances;
        this.attempts = attempts;
        this.quoteApi = quoteApi;
        this.accounts = accounts;
        this.ledger = ledger;
        this.gateway = gateway;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // =====================================================================================
    // SEND
    // =====================================================================================

    /** Sends the request for a reserved payout. Safe to call again: the reference never changes. */
    public Remittance submit(String reference) {
        Remittance r = load(reference);
        if (isFinal(r)) {
            return r;
        }
        PayoutQuoteView quote = quoteApi.find(r.getQuoteId())
                .orElseThrow(() -> new IllegalStateException("Quote missing for payout " + reference));
        boolean firstSend = r.getProviderStatus() == null;

        Remittance after;
        try {
            after = apply(reference, gateway.initiatePayout(requestFor(r, quote)));
        } catch (PayoutUnavailableException e) {
            after = markUnknown(reference);                                   // R-PAY-9: stay PENDING, refund nothing
        } catch (PayoutRejectedException e) {
            return refund(reference, e.getMessage());                         // nothing was sent: refund at once
        }
        if (firstSend && after.getStatus() == RemittanceStatus.PENDING) {
            announce(after, quote, TransferStage.ACCEPTED);                   // R-NTF-7: debited and sent, outcome unknown
        }
        return after;
    }

    /** Asks the partner for the truth by our reference, then settles once. Used by the webhook, the job and a verify. */
    public Remittance settleFromGateway(String reference) {
        Remittance r = load(reference);
        if (isFinal(r)) {
            return r;
        }
        PayoutResult result = gateway.verifyPayout(reference);                // no transaction open
        if (result.status() == PayoutStatus.NOT_FOUND) {
            return submit(reference);                                         // never received: send again, same reference
        }
        return apply(reference, result);
    }

    private Remittance apply(String reference, PayoutResult result) {
        return switch (result.status()) {
            case SUCCESS -> settleSuccess(reference, result);
            case FAILED -> refund(reference, result.message());
            case PENDING -> recordPending(reference, result.providerRef());
            case NOT_FOUND -> load(reference);
        };
    }

    // =====================================================================================
    // SETTLE (each in its own short transaction, under the row lock)
    // =====================================================================================

    private Remittance recordPending(String reference, String providerRef) {
        return tx.execute(status -> {
            Remittance r = lock(reference);
            if (isOpen(r)) {
                r.setProviderPayoutRef(providerRef);
                r.setProviderStatus(PayoutStatus.PENDING.name());
                r.setUpdatedAt(clock.instant());
                remittances.save(r);
                recordAttempt(r, PayoutStatus.PENDING.name());
            }
            return r;
        });
    }

    private Remittance markUnknown(String reference) {
        return tx.execute(status -> {
            Remittance r = lock(reference);
            if (isOpen(r)) {
                r.setProviderStatus("UNKNOWN");
                r.setUpdatedAt(clock.instant());
                remittances.save(r);
            }
            return r;
        });
    }

    private Remittance settleSuccess(String reference, PayoutResult result) {
        return tx.execute(status -> {
            Remittance r = lock(reference);
            if (!isOpen(r)) {
                return r;                                                     // a duplicate success changes nothing
            }
            PayoutQuoteView quote = quoteOf(r);
            boolean conversion = !r.getSourceCurrency().equals(r.getPayoutCurrency());
            long net = quote.netMinor();

            JournalDraft paid = conversion
                    ? PayoutJournals.paidWithConversion(reference, r.getSourceCurrency(), r.getPayoutCurrency(),
                    r.getSourceAmountMinor(), net, r.getFeeMinor(), r.getPayoutAmountMinor())
                    : PayoutJournals.paidNoConversion(reference, r.getSourceCurrency(), r.getSourceAmountMinor(),
                    net, r.getFeeMinor());
            post(paid);

            // J6 (OWN_LOCK): the partner's real cost against what we quoted. PayBridge carries any difference; the
            // customer's debit and the recipient's amount never change (R-PAY-7a).
            if (conversion && "OWN_LOCK".equalsIgnoreCase(quote.rateMode()) && result.realSourceCostMinor() != null) {
                long difference = result.realSourceCostMinor() - net;
                r.setRateDifferenceMinor(difference);
                if (difference != 0) {
                    post(PayoutJournals.rateDifference(reference, r.getSourceCurrency(), difference));
                    audit.record("system", "PAYOUT_RATE_DIFFERENCE", reference,
                            "difference " + difference + " " + r.getSourceCurrency() + " (minor units) carried by PayBridge");
                }
            }

            Instant now = clock.instant();
            r.setConversionDone(conversion);
            r.setStatus(RemittanceStatus.SUCCESSFUL);
            r.setProviderPayoutRef(result.providerRef());
            r.setProviderStatus(PayoutStatus.SUCCESS.name());
            r.setCompletedAt(now);
            r.setUpdatedAt(now);
            remittances.save(r);
            recordAttempt(r, PayoutStatus.SUCCESS.name());
            quoteApi.complete(r.getQuoteId(), paid.key());

            audit.record("system", "PAYOUT_SUCCEEDED", reference, "payout to " + mask(quote.accountNumber()) + " succeeded");
            announce(r, quote, TransferStage.SUCCEEDED);
            return r;
        });
    }

    /** J4: the sender gets everything back, fee included, in the source currency (R-PAY-10). */
    private Remittance refund(String reference, String reason) {
        return tx.execute(status -> {
            Remittance r = lock(reference);
            if (!isOpen(r)) {
                return r;
            }
            PayoutQuoteView quote = quoteOf(r);

            accounts.lockWalletsInOrder(List.of(r.getSourceAccountNumber()), r.getSourceCurrency());
            accounts.credit(r.getSourceAccountNumber(), r.getSourceCurrency(), r.getSourceAmountMinor());
            post(PayoutJournals.refund(reference, r.getSourceAccountNumber(), r.getSourceCurrency(),
                    r.getSourceAmountMinor()));

            Instant now = clock.instant();
            r.setStatus(RemittanceStatus.FAILED);
            r.setFailureReason(reason == null || reason.isBlank() ? "The payout failed." : reason);
            r.setProviderStatus(PayoutStatus.FAILED.name());
            r.setCompletedAt(now);
            r.setUpdatedAt(now);
            remittances.save(r);
            recordAttempt(r, PayoutStatus.FAILED.name());
            quoteApi.fail(r.getQuoteId(), "Payout failed.");

            audit.record("system", "PAYOUT_REFUNDED", reference,
                    "refunded " + r.getSourceAmountMinor() + " " + r.getSourceCurrency() + " (minor units), fee included");
            announce(r, quote, TransferStage.FAILED);
            return r;
        });
    }

    // =====================================================================================
    // HELPERS
    // =====================================================================================

    private PayoutRequest requestFor(Remittance r, PayoutQuoteView q) {
        // OWN_LOCK: the exact amount goes with the request. PROVIDER_LOCK: the stored rate id goes too (R-PAY-7).
        return new PayoutRequest(r.getReference(), r.getSourceCurrency(), r.getPayoutCurrency(),
                r.getPayoutAmountMinor(), q.country(), q.bankCode(), q.accountNumber(), q.accountName(),
                q.providerRateRef(), q.netMinor(), q.purpose());
    }

    private void post(JournalDraft d) {
        ledger.recordJournal(d.key(), d.reference(), d.entryType(), d.lines());
    }

    private void recordAttempt(Remittance r, String result) {
        attempts.findByRemittanceIdOrderByAttemptAsc(r.getId()).stream()
                .filter(a -> a.getAttempt() == 0).findFirst()
                .ifPresent(a -> {
                    a.setResult(result);
                    attempts.save(a);
                });
    }

    /** Amount shown is what the sender sent (net); charges are the fee; total is what was debited (like Paystack). */
    private void announce(Remittance r, PayoutQuoteView q, TransferStage stage) {
        events.publishEvent(new TransferEvent(r.getReference(), TransferType.EXTERNAL, stage,
                r.getSourceAccountNumber(), q.accountNumber(), null, r.getSourceCurrency(),
                r.getSourceAmountMinor() - r.getFeeMinor(), r.getFeeMinor(), r.getSourceAmountMinor(), false,
                r.getPayoutCurrency(), r.getPayoutAmountMinor()));
    }

    private PayoutQuoteView quoteOf(Remittance r) {
        return quoteApi.find(r.getQuoteId())
                .orElseThrow(() -> new IllegalStateException("Quote missing for payout " + r.getReference()));
    }

    private Remittance load(String reference) {
        return remittances.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("PAYOUT_NOT_FOUND", "Payout not found."));
    }

    private Remittance lock(String reference) {
        return remittances.lockByReference(reference)
                .orElseThrow(() -> ApiException.notFound("PAYOUT_NOT_FOUND", "Payout not found."));
    }

    private static boolean isOpen(Remittance r) {
        return r.getStatus() == RemittanceStatus.PENDING || r.getStatus() == RemittanceStatus.RETRYING;
    }

    private static boolean isFinal(Remittance r) {
        return !isOpen(r);
    }

    static String mask(String account) {
        return account == null || account.length() <= 4 ? "****" : "****" + account.substring(account.length() - 4);
    }
}