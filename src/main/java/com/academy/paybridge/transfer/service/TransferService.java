package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudApi;
import com.academy.paybridge.compliance.api.FraudContext;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferEvent;
import com.academy.paybridge.transfer.api.TransferStage;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.charges.ChargeCalculator;
import com.academy.paybridge.transfer.charges.Charges;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.gateway.Bank;
import com.academy.paybridge.transfer.gateway.GatewayRejectedException;
import com.academy.paybridge.transfer.gateway.GatewayUnavailableException;
import com.academy.paybridge.transfer.gateway.ResolvedAccount;
import com.academy.paybridge.transfer.gateway.TransferGateway;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the two kinds of transfer.
 *
 * INTERNAL (PayBridge account to PayBridge account) is all or nothing: one transaction.
 * EXTERNAL (payout to a bank through the gateway) cannot be one transaction, because the provider
 * is someone else's computer. So: resolve, reserve (debit and save PENDING, committed), call the provider
 * OUTSIDE any transaction, then settle to SUCCESSFUL or FAILED (with refund). See TransferSettlementService.
 *
 * This class itself is not @Transactional on purpose: it uses TransactionTemplate for the parts that must
 * be atomic, so the PIN check and the provider call can sit outside them.
 */
@Service
public class TransferService {

    private final TransferRepository transfers;
    private final AccountApi accounts;
    private final CustomerApi customers;
    private final FraudApi fraud;
    private final ChargeCalculator chargeCalculator;
    private final TransferGateway gateway;
    private final BankDirectory banks;
    private final TransferSettlementService settlement;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public TransferService(TransferRepository transfers, AccountApi accounts, CustomerApi customers, FraudApi fraud,
                           ChargeCalculator chargeCalculator, TransferGateway gateway,
                           BankDirectory banks, TransferSettlementService settlement, AuditService audit,
                           ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock,
                           AppProperties appProps) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.customers = customers;
        this.fraud = fraud;
        this.chargeCalculator = chargeCalculator;
        this.gateway = gateway;
        this.banks = banks;
        this.settlement = settlement;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.zone = ZoneId.of(appProps.zone() == null ? "Africa/Lagos" : appProps.zone());
    }

    // =====================================================================================
    // INTERNAL TRANSFER
    // =====================================================================================

    public TransferResult transferInternal(Long customerId, String source, String destination, long amountKobo,
                                           String narration, String pin, String key) {
        String actor = "customer:" + customerId;
        if (source.equals(destination)) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "You cannot send money to the same account.");
        }
        accounts.getOwned(source, customerId);                 // 404 if it is not yours
        accounts.getCustomerAccount(destination);              // 404 if it does not exist (or is a system account)
        String hash = RequestHasher.hash("INTERNAL", source, destination, null, amountKobo, narration);

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        customers.verifyPin(customerId, pin);                  // own transaction: wrong tries are counted

        try {
            return tx.execute(status -> doInternal(actor, customerId, source, destination, amountKobo, narration, key, hash));
        } catch (ApiException e) {
            if (!e.getCode().equals("TRANSFER_NOT_APPROVED") && !e.getCode().equals("IDEMPOTENCY_KEY_REUSED")) {
                audit.recordIndependently(actor, "TRANSFER_REJECTED", null, e.getCode());
            }
            throw e;
        }
    }

    private TransferResult doInternal(String actor, Long customerId, String source, String destination, long amountKobo,
                                      String narration, String key, String hash) {
        Charges charges = chargeCalculator.calculate(amountKobo, TransferType.INTERNAL);
        List<String> toLock = new ArrayList<>(List.of(source, destination));
        addChargeAccounts(toLock, charges);
        accounts.lockInOrder(toLock);            // everyone locks in the same order, so no deadlock

        // The source row is locked now, so a second request with the same key waits here and then sees the first one.
        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        boolean flagged = screen(customerId, source, destination, amountKobo, "");

        long total = amountKobo + charges.totalKobo();
        accounts.debit(source, total);
        accounts.credit(destination, amountKobo);
        creditChargeAccounts(charges);

        Transfer t = new Transfer(newReference(), TransferType.INTERNAL, TransferStatus.SUCCESSFUL, source, destination,
                amountKobo, charges.feeKobo(), charges.vatKobo(), charges.stampDutyKobo(), narration, key, hash,
                clock.instant());
        transfers.save(t);
        audit.record(actor, "TRANSFER_COMPLETED", t.getReference(),
                "internal " + Money.format(amountKobo) + " from " + Money.maskAccount(source)
                        + " to " + Money.maskAccount(destination));
        audit.record(actor, "CHARGES_APPLIED", t.getReference(),
                "fee " + charges.feeKobo() + " vat " + charges.vatKobo() + " stamp " + charges.stampDutyKobo() + " kobo");
        events.publishEvent(new TransferEvent(t.getReference(), TransferType.INTERNAL, TransferStage.COMPLETED,
                source, destination, null, amountKobo, charges.totalKobo(), total, flagged));
        return new TransferResult(t, false);
    }

    // =====================================================================================
    // EXTERNAL TRANSFER (payout to a bank)
    // =====================================================================================

    public TransferResult transferExternal(Long customerId, String source, String bankCode, String destinationAccount,
                                           long amountKobo, String narration, String pin, String key) {
        String actor = "customer:" + customerId;
        accounts.getOwned(source, customerId);
        Bank bank = banks.find(bankCode)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_BANK", "That bank is not supported."));
        String hash = RequestHasher.hash("EXTERNAL", source, destinationAccount, bankCode, amountKobo, narration);

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        customers.verifyPin(customerId, pin);

        // Steps 1 and 2: check who we are paying. Nothing is debited yet, so any failure here costs nothing.
        ResolvedAccount resolved;
        String recipientCode;
        try {
            resolved = gateway.resolveAccount(destinationAccount, bankCode);
            recipientCode = gateway.createRecipient(resolved.accountName(), destinationAccount, bankCode);
        } catch (GatewayRejectedException e) {
            throw ApiException.unprocessable("ACCOUNT_NOT_RESOLVED",
                    "We could not find that bank account. Check the bank and account number.");
        } catch (GatewayUnavailableException e) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "We could not reach the bank network. Nothing was debited. Please try again.");
        }

        // Step 3: reserve. Debit and save PENDING in ONE short transaction, then commit.
        TransferResult reserved;
        try {
            reserved = tx.execute(status -> reserve(actor, customerId, source, destinationAccount, bank, resolved,
                    recipientCode, amountKobo, narration, key, hash));
        } catch (ApiException e) {
            if (!e.getCode().equals("TRANSFER_NOT_APPROVED") && !e.getCode().equals("IDEMPOTENCY_KEY_REUSED")) {
                audit.recordIndependently(actor, "TRANSFER_REJECTED", null, e.getCode());
            }
            throw e;
        }
        if (reserved.replayed()) {
            return reserved;
        }

        // Step 4: call the provider with NO transaction open. The outcome is handled in the settlement service.
        String reference = reserved.transfer().getReference();
        settlement.dispatch(reference);
        return new TransferResult(transfers.findByReference(reference).orElseThrow(), false);
    }

    private TransferResult reserve(String actor, Long customerId, String source, String destinationAccount, Bank bank,
                                   ResolvedAccount resolved, String recipientCode, long amountKobo, String narration,
                                   String key, String hash) {
        accounts.lockInOrder(List.of(source));
        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        boolean flagged = screen(customerId, source, destinationAccount, amountKobo, bank.code());

        Charges charges = chargeCalculator.calculate(amountKobo, TransferType.EXTERNAL);
        long total = amountKobo + charges.totalKobo();
        accounts.debit(source, total);

        Transfer t = new Transfer(newReference(), TransferType.EXTERNAL, TransferStatus.PENDING, source,
                destinationAccount, amountKobo, charges.feeKobo(), charges.vatKobo(), charges.stampDutyKobo(),
                narration, key, hash, clock.instant());
        t.setBank(bank.code(), bank.name(), resolved.accountName(), recipientCode);
        t.setNote("Money reserved. Sending to the bank.");
        transfers.save(t);
        audit.record(actor, "TRANSFER_RESERVED", t.getReference(),
                "payout " + Money.format(amountKobo) + " from " + Money.maskAccount(source) + " to "
                        + bank.name() + " " + Money.maskAccount(destinationAccount));
        audit.record(actor, "CHARGES_APPLIED", t.getReference(),
                "fee " + charges.feeKobo() + " vat " + charges.vatKobo() + " stamp " + charges.stampDutyKobo() + " kobo");
        events.publishEvent(new TransferEvent(t.getReference(), TransferType.EXTERNAL, TransferStage.ACCEPTED, source,
                destinationAccount, bank.name(), amountKobo, charges.totalKobo(), total, flagged));
        return new TransferResult(t, false);
    }

    // =====================================================================================
    // READING, VERIFYING, QUOTING
    // =====================================================================================

    /** Asks the provider about a pending payout. Only the owner of the source account may do this. */
    public Transfer verify(Long customerId, String reference) {
        Transfer t = getVisible(customerId, reference);
        if (t.getType() == TransferType.EXTERNAL && t.getStatus() == TransferStatus.PENDING) {
            return settlement.settleFromGateway(reference);
        }
        return t;
    }

    /** The sender can always see a transfer. The receiver of an internal transfer can see it too. Everyone else gets 404. */
    public Transfer getVisible(Long customerId, String reference) {
        Transfer t = transfers.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found."));
        boolean sender = accounts.ownerOf(t.getSourceAccountNumber()).filter(customerId::equals).isPresent();
        boolean receiver = t.getType() == TransferType.INTERNAL
                && accounts.ownerOf(t.getDestinationAccountNumber()).filter(customerId::equals).isPresent();
        if (!sender && !receiver) {
            audit.recordIndependently("customer:" + customerId, "ACCESS_DENIED_OWNERSHIP", reference, "transfer");
            throw ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer not found.");
        }
        return t;
    }

    public Page<Transfer> history(Long customerId, String accountNumber, int page, int size) {
        accounts.getOwned(accountNumber, customerId);
        return transfers.history(accountNumber, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    public Charges quote(long amountKobo, TransferType type) {
        return chargeCalculator.calculate(amountKobo, type);
    }

    // =====================================================================================
    // HELPERS
    // =====================================================================================

    /**
     * Idempotency. Same key + same request = return the stored transfer. Same key + different request = refuse.
     * The key belongs to the SOURCE account, so one customer can never replay another customer's transfer.
     */
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

    /**
     * Runs the fraud rules. BLOCK refuses the transfer (the real reasons go to the audit log only).
     * FLAG lets it through and returns true so the owner is warned.
     */
    private boolean screen(Long customerId, String source, String destinationAccount, long amountKobo, String bankCode) {
        Instant now = clock.instant();
        Instant startOfDay = clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        FraudContext ctx = new FraudContext(
                customerId, source,
                bankCode.isEmpty() ? destinationAccount : bankCode + ":" + destinationAccount,
                amountKobo,
                transfers.sumOutgoingSince(source, startOfDay, TransferStatus.FAILED),
                (int) transfers.countOutgoingSince(source,
                        now.minus(fraud.velocityWindowMinutes(), ChronoUnit.MINUTES), TransferStatus.FAILED),
                transfers.countPreviousTo(source, destinationAccount, bankCode, TransferStatus.FAILED) == 0,
                transfers.sumIncomingSince(source, TransferType.INTERNAL, TransferStatus.SUCCESSFUL,
                        now.minus(fraud.passThroughWindowMinutes(), ChronoUnit.MINUTES)));
        FraudResult result = fraud.evaluate(ctx);
        String actor = "customer:" + customerId;
        if (result.decision() == Decision.BLOCK) {
            fraud.record(null, source, result);
            audit.recordIndependently(actor, "FRAUD_BLOCKED", null,
                    Money.maskAccount(source) + ": " + result.joinedReasons());
            // Generic on purpose: do not teach an attacker which limit to stay under.
            throw ApiException.forbidden("TRANSFER_NOT_APPROVED", "This transfer was not approved. Contact support.");
        }
        if (result.decision() == Decision.FLAG) {
            fraud.record(null, source, result);
            audit.recordIndependently(actor, "FRAUD_FLAGGED", null,
                    Money.maskAccount(source) + ": " + result.joinedReasons());
            return true;
        }
        return false;
    }

    private static void addChargeAccounts(List<String> toLock, Charges c) {
        if (c.feeKobo() > 0) {
            toLock.add(AccountApi.FEE_ACCOUNT);
        }
        if (c.vatKobo() > 0) {
            toLock.add(AccountApi.VAT_ACCOUNT);
        }
        if (c.stampDutyKobo() > 0) {
            toLock.add(AccountApi.STAMP_DUTY_ACCOUNT);
        }
    }

    private void creditChargeAccounts(Charges c) {
        if (c.feeKobo() > 0) {
            accounts.credit(AccountApi.FEE_ACCOUNT, c.feeKobo());
        }
        if (c.vatKobo() > 0) {
            accounts.credit(AccountApi.VAT_ACCOUNT, c.vatKobo());
        }
        if (c.stampDutyKobo() > 0) {
            accounts.credit(AccountApi.STAMP_DUTY_ACCOUNT, c.stampDutyKobo());
        }
    }

    /** Our own reference: trf_ plus 32 hex characters (36 in total, within Paystack's 16 to 50 lowercase rule). */
    private static String newReference() {
        return "trf_" + UUID.randomUUID().toString().replace("-", "");
    }
}
