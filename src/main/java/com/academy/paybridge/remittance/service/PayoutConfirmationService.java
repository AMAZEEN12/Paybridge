package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.fx.api.PayoutQuoteApi;
import com.academy.paybridge.fx.api.PayoutQuoteApi.PayoutQuoteView;
import com.academy.paybridge.ledger.api.JournalDraft;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PayoutJournals;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceAttempt;
import com.academy.paybridge.remittance.repository.RemittanceAttemptRepository;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirming a payout quote (R-PAY-6): reserve in ONE transaction (re-check, claim the quote, debit, write J1, save
 * the payout PENDING), then call the partner with NO transaction open, then settle in short transactions.
 */
@Service
public class PayoutConfirmationService {

    public record Confirmed(Remittance remittance, boolean replayed) {
    }

    private final AccountApi accounts;
    private final CustomerApi customers;
    private final PayoutQuoteApi quoteApi;
    private final PayoutPolicyService policy;
    private final RemittanceRepository remittances;
    private final RemittanceAttemptRepository attempts;
    private final LedgerApi ledger;
    private final AuditService audit;
    private final PayoutSettlementService settlement;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayoutConfirmationService(AccountApi accounts, CustomerApi customers, PayoutQuoteApi quoteApi,
                                     PayoutPolicyService policy, RemittanceRepository remittances,
                                     RemittanceAttemptRepository attempts, LedgerApi ledger, AuditService audit,
                                     PayoutSettlementService settlement, PlatformTransactionManager txManager,
                                     Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.quoteApi = quoteApi;
        this.policy = policy;
        this.remittances = remittances;
        this.attempts = attempts;
        this.ledger = ledger;
        this.audit = audit;
        this.settlement = settlement;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public Confirmed confirm(Long customerId, UUID quoteId, String pin, String idempotencyKey) {
        String account = accounts.existingFor(customerId).accountNumber();
        String hash = sha256(quoteId.toString());

        // The same key and quote returns the first answer; the same key with another quote is refused.
        Optional<Remittance> seen = replay(account, idempotencyKey, hash);
        if (seen.isPresent()) {
            return new Confirmed(seen.get(), true);
        }

        customers.verifyPin(customerId, pin);                         // before the claim: a wrong PIN burns nothing

        String reference = "pout_" + sha256(account + ":" + idempotencyKey).substring(0, 32);   // R-PAY-7
        Remittance reserved;
        try {
            reserved = tx.execute(status -> reserve(customerId, account, quoteId, reference, idempotencyKey, hash));
        } catch (DataIntegrityViolationException e) {
            // two requests with the same key at the same moment: the loser returns the winner's payout
            return new Confirmed(replay(account, idempotencyKey, hash).orElseThrow(() -> e), true);
        }
        return new Confirmed(settlement.submit(reserved.getReference()), false);   // no transaction open here
    }

    private Remittance reserve(Long customerId, String account, UUID quoteId, String reference, String key, String hash) {
        PayoutQuoteView quote = quoteApi.find(quoteId)
                .filter(q -> q.customerId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found."));

        policy.checkCorridor(customerId, quote.country(), quote.sourceCurrency());   // R-COR-4: switching a rule off stops old quotes          // R-COR-4: switching a rule off stops old quotes
        policy.checkLimits(customerId, account, quote.usdEquivalentMinor());   // R-LIM2-2, before this quote counts

        if (accounts.isFrozen(account)) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to send money.");
        }
        String source = quote.sourceCurrency();
        accounts.lockWalletsInOrder(List.of(account), source);
        WalletView wallet = accounts.findWallet(account, source).orElseThrow(
                () -> ApiException.notFound("WALLET_NOT_FOUND", "You do not have a " + source + " wallet."));
        if ("PARKED".equals(wallet.status())) {
            throw ApiException.unprocessable("WALLET_PARKED", "This wallet is parked. Reactivate it to send from it.");
        }
        if (wallet.balanceMinor() < quote.sourceAmountMinor()) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "You do not have enough money in your " + source + " wallet.");
        }

        PayoutQuoteView claimed = quoteApi.claim(quoteId, customerId);        // single use, owner only, unexpired
        accounts.debit(account, source, claimed.sourceAmountMinor());         // the debit the ledger line mirrors

        Instant now = clock.instant();
        Remittance remittance = remittances.saveAndFlush(new Remittance(reference, customerId, quoteId, account, source,
                claimed.payoutCurrency(), claimed.sourceAmountMinor(), claimed.feeMinor(), claimed.payoutAmountMinor(),
                claimed.purpose(), key, hash, now));

        JournalDraft j1 = PayoutJournals.reserve(reference, account, source, claimed.sourceAmountMinor());
        ledger.recordJournal(j1.key(), j1.reference(), j1.entryType(), j1.lines());
        attempts.save(new RemittanceAttempt(remittance.getId(), 0, reference, null, "SUBMITTED", now));

        audit.record("customer:" + customerId, "PAYOUT_RESERVED", reference,
                "reserved " + claimed.sourceAmountMinor() + " " + source + " (minor units) for a payout to "
                        + PayoutSettlementService.mask(claimed.accountNumber()) + " in " + claimed.country());
        return remittance;
    }

    private Optional<Remittance> replay(String account, String key, String hash) {
        Optional<Remittance> existing = remittances.findBySourceAccountNumberAndIdempotencyKey(account, key);
        if (existing.isPresent() && !existing.get().getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request. Use a new key.");
        }
        return existing;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}