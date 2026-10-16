package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.compliance.api.*;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.fx.api.RateApi;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private static final String NGN = "NGN";

    /**
     * Same-currency transfers outside Naira carry no PayBridge charge or stamp duty.
     */
    private static final Charges NO_CHARGES = new Charges(0L, 0L, 0L);

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
    private final LedgerApi ledger;
    private final CurrencyRegistry currencies;
    private final TransferLimitApi limits;
    private final RateApi rates;

    public TransferService(
            TransferRepository transfers,
            AccountApi accounts,
            CustomerApi customers,
            FraudApi fraud,
            ChargeCalculator chargeCalculator,
            TransferGateway gateway,
            BankDirectory banks,
            TransferSettlementService settlement,
            AuditService audit,
            ApplicationEventPublisher events,
            PlatformTransactionManager txManager,
            Clock clock,
            AppProperties appProps,
            LedgerApi ledger,
            CurrencyRegistry currencies,
            TransferLimitApi limits,
            RateApi rates) {

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
        this.zone = ZoneId.of(
                appProps.zone() == null ? "Africa/Lagos" : appProps.zone());
        this.ledger = ledger;
        this.currencies = currencies;
        this.limits = limits;
        this.rates = rates;
    }

    // ============================================================
    // INTERNAL TRANSFER
    // ============================================================

    /**
     * Same-currency transfer: the sender pays from their wallet in
     * the selected currency, and the recipient receives the same amount.
     */
    public TransferResult transferInternal(
            Long customerId,
            String source,
            String destination,
            String currency,
            long amountMinor,
            String narration,
            String pin,
            String key) {

        String actor = "customer:" + customerId;

        if (source.equals(destination)) {
            throw ApiException.unprocessable(
                    "SAME_ACCOUNT",
                    "You cannot send money to the same account.");
        }

        accounts.getOwned(source, customerId);
        accounts.getCustomerAccount(destination);
        requireReceivable(destination, currency);

        if (accounts.findWallet(source, currency).isEmpty()) {
            throw ApiException.notFound(
                    "WALLET_NOT_FOUND",
                    "You do not have a " + currency + " wallet.");
        }

        String hash = RequestHasher.hash(
                "INTERNAL",
                source,
                destination,
                NGN.equals(currency) ? null : currency,
                amountMinor,
                narration);

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        customers.verifyPin(customerId, pin);

        // Obtain the USD equivalent before opening the transaction.
        // NGN transfers retain the existing Naira flow.
        long usdMinor = NGN.equals(currency)
                ? 0L
                : usdEquivalent(amountMinor, currency);

        try {
            return tx.execute(status -> doInternal(
                    actor,
                    customerId,
                    source,
                    destination,
                    currency,
                    amountMinor,
                    usdMinor,
                    narration,
                    key,
                    hash));
        } catch (ApiException e) {
            if (!e.getCode().equals("TRANSFER_NOT_APPROVED")
                    && !e.getCode().equals("IDEMPOTENCY_KEY_REUSED")) {
                audit.recordIndependently(
                        actor, "TRANSFER_REJECTED", null, e.getCode());
            }
            throw e;
        }
    }

    private TransferResult doInternal(
            String actor,
            Long customerId,
            String source,
            String destination,
            String currency,
            long amountMinor,
            long usdMinor,
            String narration,
            String key,
            String hash) {

        boolean naira = NGN.equals(currency);

        Charges charges = naira
                ? chargeCalculator.calculate(amountMinor, TransferType.INTERNAL)
                : NO_CHARGES;

        accounts.lockWalletsInOrder(List.of(source, destination), currency);

        List<String> chargeAccounts = new ArrayList<>();
        addChargeAccounts(chargeAccounts, charges);

        if (!chargeAccounts.isEmpty()) {
            accounts.lockInOrder(chargeAccounts);
        }

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (!naira) {
            checkTransferLimit(customerId, source, usdMinor);
        }

        boolean flagged = naira
                && screen(customerId, source, destination, amountMinor, "");

        long total = amountMinor + charges.totalKobo();

        accounts.debit(source, currency, total);
        accounts.credit(destination, currency, amountMinor);
        creditChargeAccounts(charges);

        Transfer t = new Transfer(
                newReference(),
                TransferType.INTERNAL,
                TransferStatus.SUCCESSFUL,
                source,
                destination,
                currency,
                amountMinor,
                charges.feeKobo(),
                charges.vatKobo(),
                charges.stampDutyKobo(),
                narration,
                key,
                hash,
                clock.instant());

        t.setUsdEquivalentMinor(usdMinor);
        transfers.save(t);

        if (naira) {
            ledger.recordInternal(
                    t.getReference(),
                    source,
                    destination,
                    amountMinor,
                    charges.feeKobo(),
                    charges.vatKobo(),
                    charges.stampDutyKobo());
        } else {
            ledger.recordJournal(
                    "XFER:" + t.getReference(),
                    t.getReference(),
                    "TRANSFER",
                    List.of(
                            new JournalLine(
                                    LedgerApi.walletKey(source, currency),
                                    JournalLine.Side.DEBIT,
                                    currency,
                                    amountMinor),
                            new JournalLine(
                                    LedgerApi.walletKey(destination, currency),
                                    JournalLine.Side.CREDIT,
                                    currency,
                                    amountMinor)));
        }

        audit.record(
                actor,
                "TRANSFER_COMPLETED",
                t.getReference(),
                "internal " + show(amountMinor, currency)
                        + " from " + Money.maskAccount(source)
                        + " to " + Money.maskAccount(destination));

        if (naira) {
            audit.record(
                    actor,
                    "CHARGES_APPLIED",
                    t.getReference(),
                    "fee " + charges.feeKobo()
                            + " vat " + charges.vatKobo()
                            + " stamp " + charges.stampDutyKobo()
                            + " kobo");
        }

        events.publishEvent(new TransferEvent(
                t.getReference(),
                TransferType.INTERNAL,
                TransferStage.COMPLETED,
                source,
                destination,
                null,
                currency,
                amountMinor,
                charges.totalKobo(),
                total,
                flagged));

        return new TransferResult(t, false);
    }

    // ============================================================
    // EXTERNAL TRANSFER
    // ============================================================

    public TransferResult transferExternal(
            Long customerId,
            String source,
            String bankCode,
            String destinationAccount,
            long amountKobo,
            String narration,
            String pin,
            String key) {

        String actor = "customer:" + customerId;

        accounts.getOwned(source, customerId);

        Bank bank = banks.find(bankCode)
                .orElseThrow(() -> ApiException.unprocessable(
                        "UNKNOWN_BANK",
                        "That bank is not supported."));

        String hash = RequestHasher.hash(
                "EXTERNAL",
                source,
                destinationAccount,
                bankCode,
                amountKobo,
                narration);

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        customers.verifyPin(customerId, pin);

        ResolvedAccount resolved;
        String recipientCode;

        try {
            resolved = gateway.resolveAccount(destinationAccount, bankCode);
            recipientCode = gateway.createRecipient(
                    resolved.accountName(),
                    destinationAccount,
                    bankCode);

        } catch (com.academy.paybridge.transfer.gateway.LookupLimitReachedException e) {
            throw ApiException.tooManyRequests(
                    "LOOKUP_LIMIT_REACHED",
                    e.getMessage() + " Nothing was debited.");

        } catch (GatewayRejectedException e) {
            org.slf4j.LoggerFactory.getLogger(TransferService.class).warn(
                    "Paystack refused account check/recipient for bank {} (HTTP {}): {}",
                    bankCode,
                    e.getHttpStatus(),
                    e.getMessage());

            throw ApiException.unprocessable(
                    "ACCOUNT_NOT_RESOLVED",
                    "We could not find that bank account. Check the bank and account number.");

        } catch (GatewayUnavailableException e) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    "We could not reach the bank network. Nothing was debited. Please try again.");
        }

        TransferResult reserved;

        try {
            reserved = tx.execute(status -> reserve(
                    actor,
                    customerId,
                    source,
                    destinationAccount,
                    bank,
                    resolved,
                    recipientCode,
                    amountKobo,
                    narration,
                    key,
                    hash));

        } catch (ApiException e) {
            if (!e.getCode().equals("TRANSFER_NOT_APPROVED")
                    && !e.getCode().equals("IDEMPOTENCY_KEY_REUSED")) {
                audit.recordIndependently(
                        actor, "TRANSFER_REJECTED", null, e.getCode());
            }
            throw e;
        }

        if (reserved.replayed()) {
            return reserved;
        }

        String reference = reserved.transfer().getReference();

        settlement.dispatch(reference);

        return new TransferResult(
                transfers.findByReference(reference).orElseThrow(),
                false);
    }

    private TransferResult reserve(
            String actor,
            Long customerId,
            String source,
            String destinationAccount,
            Bank bank,
            ResolvedAccount resolved,
            String recipientCode,
            long amountKobo,
            String narration,
            String key,
            String hash) {

        accounts.lockInOrder(List.of(source));

        Optional<TransferResult> replay = replayIfSeen(source, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        boolean flagged = screen(
                customerId,
                source,
                destinationAccount,
                amountKobo,
                bank.code());

        Charges charges = chargeCalculator.calculate(
                amountKobo, TransferType.EXTERNAL);

        long total = amountKobo + charges.totalKobo();

        accounts.debit(source, NGN, total);

        Transfer t = new Transfer(
                newReference(),
                TransferType.EXTERNAL,
                TransferStatus.PENDING,
                source,
                destinationAccount,
                NGN,
                amountKobo,
                charges.feeKobo(),
                charges.vatKobo(),
                charges.stampDutyKobo(),
                narration,
                key,
                hash,
                clock.instant());

        t.setBank(
                bank.code(),
                bank.name(),
                resolved.accountName(),
                recipientCode);

        t.setNote("Money reserved. Sending to the bank.");

        transfers.save(t);

        ledger.recordReserve(t.getReference(), source, total);

        audit.record(
                actor,
                "TRANSFER_RESERVED",
                t.getReference(),
                "payout " + Money.format(amountKobo)
                        + " from " + Money.maskAccount(source)
                        + " to " + bank.name()
                        + " " + Money.maskAccount(destinationAccount));

        audit.record(
                actor,
                "CHARGES_APPLIED",
                t.getReference(),
                "fee " + charges.feeKobo()
                        + " vat " + charges.vatKobo()
                        + " stamp " + charges.stampDutyKobo()
                        + " kobo");

        events.publishEvent(new TransferEvent(
                t.getReference(),
                TransferType.EXTERNAL,
                TransferStage.ACCEPTED,
                source,
                destinationAccount,
                bank.name(),
                NGN,
                amountKobo,
                charges.totalKobo(),
                total,
                flagged));

        return new TransferResult(t, false);
    }

    // ============================================================
    // READING, VERIFYING, QUOTING
    // ============================================================

    public Transfer verify(Long customerId, String reference) {
        Transfer t = getVisible(customerId, reference);

        if (t.getType() == TransferType.EXTERNAL
                && t.getStatus() == TransferStatus.PENDING) {
            return settlement.settleFromGateway(reference);
        }

        return t;
    }

    public Transfer getVisible(Long customerId, String reference) {
        Transfer t = transfers.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound(
                        "TRANSFER_NOT_FOUND",
                        "Transfer not found."));

        boolean sender = accounts.ownerOf(t.getSourceAccountNumber())
                .filter(customerId::equals)
                .isPresent();

        boolean receiver = t.getType() == TransferType.INTERNAL
                && accounts.ownerOf(t.getDestinationAccountNumber())
                .filter(customerId::equals)
                .isPresent();

        if (!sender && !receiver) {
            audit.recordIndependently(
                    "customer:" + customerId,
                    "ACCESS_DENIED_OWNERSHIP",
                    reference,
                    "transfer");

            throw ApiException.notFound(
                    "TRANSFER_NOT_FOUND",
                    "Transfer not found.");
        }

        return t;
    }

    public Page<Transfer> history(
            Long customerId,
            String accountNumber,
            int page,
            int size) {

        accounts.getOwned(accountNumber, customerId);

        return transfers.history(
                accountNumber,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.min(Math.max(size, 1), 100)));
    }

    public Charges quote(long amountKobo, TransferType type) {
        return chargeCalculator.calculate(amountKobo, type);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private Optional<TransferResult> replayIfSeen(
            String source,
            String key,
            String hash) {

        Optional<Transfer> existing =
                transfers.findBySourceAccountNumberAndIdempotencyKey(source, key);

        if (existing.isEmpty()) {
            return Optional.empty();
        }

        if (!existing.get().getRequestHash().equals(hash)) {
            throw ApiException.unprocessable(
                    "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request. Use a new key.");
        }

        return Optional.of(new TransferResult(existing.get(), true));
    }

    private boolean screen(
            Long customerId,
            String source,
            String destinationAccount,
            long amountKobo,
            String bankCode) {

        Instant now = clock.instant();

        Instant startOfDay = now
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant();

        FraudContext ctx = new FraudContext(
                customerId,
                source,
                bankCode.isEmpty()
                        ? destinationAccount
                        : bankCode + ":" + destinationAccount,
                amountKobo,
                transfers.sumOutgoingSince(
                        source, startOfDay, TransferStatus.FAILED),
                (int) transfers.countOutgoingSince(
                        source,
                        now.minus(fraud.velocityWindowMinutes(), ChronoUnit.MINUTES),
                        TransferStatus.FAILED),
                transfers.countPreviousTo(
                        source,
                        destinationAccount,
                        bankCode,
                        TransferStatus.FAILED) == 0,
                transfers.sumIncomingSince(
                        source,
                        TransferType.INTERNAL,
                        TransferStatus.SUCCESSFUL,
                        now.minus(fraud.passThroughWindowMinutes(), ChronoUnit.MINUTES)));

        FraudResult result = fraud.evaluate(ctx);

        String actor = "customer:" + customerId;

        if (result.decision() == Decision.BLOCK) {
            fraud.record(null, source, result);

            audit.recordIndependently(
                    actor,
                    "FRAUD_BLOCKED",
                    null,
                    Money.maskAccount(source) + ": " + result.joinedReasons());

            throw ApiException.forbidden(
                    "TRANSFER_NOT_APPROVED",
                    "This transfer was not approved. Contact support.");
        }

        if (result.decision() == Decision.FLAG) {
            fraud.record(null, source, result);

            audit.recordIndependently(
                    actor,
                    "FRAUD_FLAGGED",
                    null,
                    Money.maskAccount(source) + ": " + result.joinedReasons());

            return true;
        }

        return false;
    }

    /**
     * Converts a foreign-currency amount to USD minor units for limit checks.
     * Verify the RateApi method and its rate direction against your FX implementation.
     */
    private long usdEquivalent(long amountMinor, String currency) {
        BigDecimal toUsd = rates.rateWithUsd(currency, currency).toUsd();

        int decimals = currencies.find(currency)
                .map(com.academy.paybridge.shared.money.CurrencyInfo::minorUnits)
                .orElse(2);

        BigDecimal usd = BigDecimal.valueOf(amountMinor).multiply(toUsd);

        if (decimals > 2) {
            usd = usd.divide(
                    BigDecimal.TEN.pow(decimals - 2),
                    20,
                    RoundingMode.DOWN);
        } else if (decimals < 2) {
            usd = usd.multiply(BigDecimal.TEN.pow(2 - decimals));
        }

        return usd.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /**
     * Checks the per-transfer and daily USD-equivalent limit.
     * The daily total uses the start of the current day in the configured zone.
     */
    private void checkTransferLimit(
            Long customerId,
            String source,
            long usdMinor) {

        Instant startOfDay = clock.instant()
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant();

        long usedToday = transfers.sumUsdSince(
                source, startOfDay, TransferStatus.FAILED);

        FraudResult result = limits.evaluate(new TransferLimitContext(
                TransferLimitContext.Kind.TRANSFER,
                usdMinor,
                usedToday));

        if (result.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable(
                    "LIMIT_EXCEEDED",
                    result.joinedReasons());
        }
    }

    private static void addChargeAccounts(
            List<String> toLock,
            Charges charges) {

        if (charges.feeKobo() > 0) {
            toLock.add(AccountApi.FEE_ACCOUNT);
        }

        if (charges.vatKobo() > 0) {
            toLock.add(AccountApi.VAT_ACCOUNT);
        }

        if (charges.stampDutyKobo() > 0) {
            toLock.add(AccountApi.STAMP_DUTY_ACCOUNT);
        }
    }

    private void creditChargeAccounts(Charges charges) {
        if (charges.feeKobo() > 0) {
            accounts.credit(AccountApi.FEE_ACCOUNT, NGN, charges.feeKobo());
        }

        if (charges.vatKobo() > 0) {
            accounts.credit(AccountApi.VAT_ACCOUNT, NGN, charges.vatKobo());
        }

        if (charges.stampDutyKobo() > 0) {
            accounts.credit(
                    AccountApi.STAMP_DUTY_ACCOUNT,
                    NGN,
                    charges.stampDutyKobo());
        }
    }

    private static String newReference() {
        return "trf_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Naira keeps its existing wording; other currencies use their own
     * symbols and decimal places.
     */
    private String show(long minor, String currency) {
        return NGN.equals(currency)
                ? Money.format(minor)
                : currencies.format(minor, currency);
    }

    /**
     * The recipient must have a wallet in the currency being transferred.
     */
    private void requireReceivable(String destination, String currency) {
        List<String> accepted = accounts.receivableCurrencies(destination);

        if (!accepted.contains(currency)) {
            throw ApiException.unprocessable(
                    "RECIPIENT_CANNOT_RECEIVE",
                    "That account cannot receive " + currency
                            + ". It accepts: " + String.join(", ", accepted) + ".");
        }
    }
}
