package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferEvent;
import com.academy.paybridge.transfer.api.TransferStage;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.gateway.GatewayRejectedException;
import com.academy.paybridge.transfer.gateway.GatewayResult;
import com.academy.paybridge.transfer.gateway.GatewayUnavailableException;
import com.academy.paybridge.transfer.gateway.TransferGateway;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that happens to an external payout AFTER the money has been reserved:
 * calling the provider, and settling the transfer to SUCCESSFUL or FAILED (with refund).
 *
 * This is a separate bean from TransferService on purpose. The provider call must happen OUTSIDE any
 * database transaction, and Spring's @Transactional does not work when a class calls its own methods.
 */
@Service
public class TransferSettlementService {

    private static final Logger log = LoggerFactory.getLogger(TransferSettlementService.class);
    private static final Duration RESEND_AFTER = Duration.ofMinutes(2);

    private final TransferRepository transfers;
    private final AccountApi accounts;
    private final TransferGateway gateway;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferSettlementService(TransferRepository transfers, AccountApi accounts, TransferGateway gateway,
                                     AuditService audit, ApplicationEventPublisher events,
                                     PlatformTransactionManager txManager, Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.gateway = gateway;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Step 3 of an external transfer: ask the provider to pay. Runs with NO open transaction. */
    public void dispatch(String reference) {
        Transfer t = transfers.findByReference(reference).orElseThrow();
        try {
            GatewayResult result = gateway.initiateTransfer(t.getReference(), t.getAmountKobo(),
                    t.getRecipientCode(), t.getNarration());
            audit.recordIndependently("system", "GATEWAY_CALLED", reference, "status " + result.status());
            apply(reference, result);
        } catch (GatewayRejectedException e) {
            // A clear "no": the money did not go, so giving it back is safe.
            audit.recordIndependently("paystack", "GATEWAY_REJECTED", reference, "http " + e.getHttpStatus());
            settle(reference, false, "Rejected by provider");
        } catch (GatewayUnavailableException e) {
            // Unknown outcome. DO NOT refund. Leave it pending; the verify job will find out.
            audit.recordIndependently("system", "GATEWAY_OUTCOME_UNKNOWN", reference, e.getMessage());
            markNote(reference, "Outcome unknown (provider did not answer). Will verify.");
        }
    }

    /** Asks the provider for the truth about a pending transfer and settles it. Used by verify, webhook and job. */
    public Transfer settleFromGateway(String reference) {
        Transfer t = transfers.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
        if (t.getStatus() != TransferStatus.PENDING) {
            return t;
        }
        GatewayResult result;
        try {
            result = gateway.verifyTransfer(reference);
        } catch (GatewayUnavailableException | GatewayRejectedException e) {
            log.warn("Could not verify {}: {}", reference, e.getClass().getSimpleName());
            return t;                        // still pending; try again later
        }
        if (result.status() == com.academy.paybridge.transfer.gateway.GatewayStatus.NOT_FOUND) {
            // The provider never got it (for example we crashed after debiting). Send it again with the SAME
            // reference after a short wait: a duplicate with the same reference cannot pay twice.
            boolean oldEnough = t.getCreatedAt().plus(RESEND_AFTER).isBefore(clock.instant());
            if (oldEnough && t.getRecipientCode() != null) {
                audit.recordIndependently("system", "GATEWAY_RESEND", reference, null);
                dispatch(reference);
            }
            return transfers.findByReference(reference).orElseThrow();
        }
        apply(reference, result);
        return transfers.findByReference(reference).orElseThrow();
    }

    private void apply(String reference, GatewayResult result) {
        switch (result.status()) {
            case SUCCESS -> settle(reference, true, null);
            case FAILED -> settle(reference, false, result.message());
            case PENDING -> saveGatewayReference(reference, result.gatewayReference());
            case NOT_FOUND -> { /* nothing to do */ }
        }
    }

    /**
     * Moves a PENDING transfer to SUCCESSFUL or FAILED, exactly once. The row is locked, only PENDING can
     * change, and the status change and the refund (or the charge credits) are in ONE transaction.
     */
    void settle(String reference, boolean success, String reason) {
        tx.executeWithoutResult(status -> {
            Transfer t = transfers.findForUpdate(reference).orElseThrow();
            if (t.getStatus() != TransferStatus.PENDING) {
                return;                      // someone else already settled it: do nothing
            }
            List<String> toLock = new ArrayList<>();
            if (success) {
                toLock.add(AccountApi.FEE_ACCOUNT);
                toLock.add(AccountApi.VAT_ACCOUNT);
                toLock.add(AccountApi.STAMP_DUTY_ACCOUNT);
            } else {
                toLock.add(t.getSourceAccountNumber());
            }
            accounts.lockInOrder(toLock);

            if (success) {
                if (t.getFeeKobo() > 0) {
                    accounts.credit(AccountApi.FEE_ACCOUNT, t.getFeeKobo());
                }
                if (t.getVatKobo() > 0) {
                    accounts.credit(AccountApi.VAT_ACCOUNT, t.getVatKobo());
                }
                if (t.getStampDutyKobo() > 0) {
                    accounts.credit(AccountApi.STAMP_DUTY_ACCOUNT, t.getStampDutyKobo());
                }
                t.setStatus(TransferStatus.SUCCESSFUL);
                t.setNote(null);
                audit.record("paystack", "TRANSFER_SUCCESSFUL", reference, null);
            } else {
                accounts.credit(t.getSourceAccountNumber(), t.getTotalDebitKobo());   // the refund, charges included
                t.setStatus(TransferStatus.FAILED);
                t.setNote(reason == null ? "Payout failed" : reason);
                audit.record("system", "TRANSFER_FAILED_REFUNDED", reference,
                        "refunded " + Money.format(t.getTotalDebitKobo()));
            }
            t.setUpdatedAt(clock.instant());
            transfers.save(t);
            events.publishEvent(new TransferEvent(t.getReference(), t.getType(),
                    success ? TransferStage.SUCCEEDED : TransferStage.FAILED,
                    t.getSourceAccountNumber(), t.getDestinationAccountNumber(), t.getDestinationBankName(),
                    t.getAmountKobo(), t.chargesKobo(), t.getTotalDebitKobo(), false));
        });
    }

    private void saveGatewayReference(String reference, String gatewayReference) {
        tx.executeWithoutResult(status -> {
            Transfer t = transfers.findForUpdate(reference).orElseThrow();
            if (t.getStatus() == TransferStatus.PENDING) {
                t.setGatewayReference(gatewayReference);
                t.setNote("Sent to the bank network. Waiting for confirmation.");
                t.setUpdatedAt(clock.instant());
                transfers.save(t);
            }
        });
    }

    private void markNote(String reference, String note) {
        tx.executeWithoutResult(status -> {
            Transfer t = transfers.findForUpdate(reference).orElseThrow();
            if (t.getStatus() == TransferStatus.PENDING) {
                t.setNote(note);
                t.setUpdatedAt(clock.instant());
                transfers.save(t);
            }
        });
    }
}
