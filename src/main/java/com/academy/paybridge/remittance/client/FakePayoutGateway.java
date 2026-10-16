package com.academy.paybridge.remittance.client;

import com.academy.paybridge.remittance.service.PayoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend payout partner for local runs, tests and demos. Active while paybridge.payouts.provider is "fake" (the
 * default). The first two digits of the ACCOUNT NUMBER decide what happens, as with the Paystack fake:
 *
 * <pre>
 * Verifying an account
 *   00   the account does not exist                       (AccountNotFoundException)
 *   11   the partner cannot check it right now            (VerificationUnavailableException, so auto falls back)
 *   else a name:  FLUTTERWAVE TEST &lt;last 4 digits&gt;
 *
 * Paying out
 *   77   rejected straight away                             (refund)
 *   88   the request "times out" but the partner DID pay    (stays PENDING; verify later says SUCCESS)
 *   99   accepted, then FAILS on verify                     (refund)
 *   55   accepted, then FAILS; reports converted-but-unpaid only when retries are on (B5)
 *   66   succeeds, real cost 2% WORSE than quoted           (journal J6, PayBridge carries it)
 *   67   succeeds, real cost 2% BETTER than quoted          (journal J6, the other way)
 *   else accepted, then SUCCEEDS on verify
 * </pre>
 *
 * The same reference twice never pays twice and never replays the request.
 */
@Component
@ConditionalOnProperty(name = "paybridge.payouts.provider", havingValue = "fake", matchIfMissing = true)
public class FakePayoutGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePayoutGateway.class);

    /** What one US dollar is worth, give or take, so the fake rates look believable. A currency missing here has no rate. */
    private static final Map<String, BigDecimal> USD_PER_UNIT = Map.ofEntries(
            Map.entry("USD", new BigDecimal("1")),
            Map.entry("NGN", new BigDecimal("0.00066")),
            Map.entry("GBP", new BigDecimal("1.27")),
            Map.entry("EUR", new BigDecimal("1.08")),
            Map.entry("GHS", new BigDecimal("0.065")),
            Map.entry("KES", new BigDecimal("0.0078")),
            Map.entry("ZAR", new BigDecimal("0.055")),
            Map.entry("CAD", new BigDecimal("0.73")),
            Map.entry("JPY", new BigDecimal("0.0067")));

    private record Outcome(PayoutStatus status, boolean convertedNotPaid, Long realCost) {
    }

    private final boolean retryEnabled;
    private final Map<String, Outcome> processed = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final AtomicInteger verifyAccountCalls = new AtomicInteger();

    @Autowired
    public FakePayoutGateway(PayoutProperties props) {
        this(props.retry().enabled());
        log.warn("PAYOUTS: the FAKE payout partner is active, so NOTHING is sent to Flutterwave. "
                + "Real payouts need step B4 and PAYBRIDGE_PAYOUT_PROVIDER=flutterwave.");
    }

    public FakePayoutGateway(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    @Override
    public VerifiedName verifyAccount(String country, String bankCode, String accountNumber) {
        verifyAccountCalls.incrementAndGet();
        if (accountNumber.startsWith("00")) {
            throw new AccountNotFoundException("Account not found.");
        }
        if (accountNumber.startsWith("11")) {
            throw new VerificationUnavailableException("The partner cannot verify this account right now.");
        }
        return new VerifiedName("FLUTTERWAVE TEST " + lastFour(accountNumber));
    }

    @Override
    public ProviderRate getRate(String sourceCurrency, String destinationCurrency) {
        BigDecimal from = USD_PER_UNIT.get(sourceCurrency);
        BigDecimal to = USD_PER_UNIT.get(destinationCurrency);
        if (from == null || to == null) {
            throw new RateUnavailableException("No rate for " + sourceCurrency + " to " + destinationCurrency + ".");
        }
        BigDecimal rate = sourceCurrency.equals(destinationCurrency)
                ? BigDecimal.ONE
                : from.divide(to, 12, RoundingMode.HALF_EVEN);
        return new ProviderRate(rate, null, null);          // no rate id: this is the OWN_LOCK shape
    }

    @Override
    public PayoutResult initiatePayout(PayoutRequest r) {
        requests.computeIfAbsent(r.reference(), k -> new AtomicInteger()).incrementAndGet();
        String ref = "FAKE_" + r.reference();
        if (processed.containsKey(r.reference())) {
            // Same reference again: the partner refuses to pay twice and just reports the first one.
            return PayoutResult.of(PayoutStatus.PENDING, ref, "Already received");
        }
        String account = r.accountNumber();
        if (account.startsWith("77")) {
            throw new PayoutRejectedException("The partner rejected the payout.");
        }
        processed.put(r.reference(), outcomeFor(account, r.quotedSourceNetMinor()));
        if (account.startsWith("88")) {
            throw new PayoutUnavailableException("Timed out waiting for the partner (but it did receive the request).");
        }
        return PayoutResult.of(PayoutStatus.PENDING, ref, "Queued");
    }

    @Override
    public PayoutResult verifyPayout(String reference) {
        Outcome o = processed.get(reference);
        if (o == null) {
            return PayoutResult.of(PayoutStatus.NOT_FOUND, null, "Unknown reference");
        }
        String ref = "FAKE_" + reference;
        return o.status() == PayoutStatus.SUCCESS
                ? new PayoutResult(PayoutStatus.SUCCESS, ref, "Paid", false, o.realCost())
                : new PayoutResult(PayoutStatus.FAILED, ref, "Failed at the receiving bank", o.convertedNotPaid(), null);
    }

    private Outcome outcomeFor(String account, long quotedNet) {
        if (account.startsWith("99")) {
            return new Outcome(PayoutStatus.FAILED, false, null);
        }
        if (account.startsWith("55")) {
            return new Outcome(PayoutStatus.FAILED, retryEnabled, null);
        }
        if (account.startsWith("66") && quotedNet > 0) {
            return new Outcome(PayoutStatus.SUCCESS, false, quotedNet + Math.max(1, quotedNet / 50));
        }
        if (account.startsWith("67") && quotedNet > 0) {
            return new Outcome(PayoutStatus.SUCCESS, false, quotedNet - Math.max(1, quotedNet / 50));
        }
        return new Outcome(PayoutStatus.SUCCESS, false, quotedNet > 0 ? quotedNet : null);
    }

    /** For tests: how many times a payout request with this reference reached the partner. */
    public int requestCount(String reference) {
        AtomicInteger n = requests.get(reference);
        return n == null ? 0 : n.get();
    }

    /** For tests: how many account verifications reached the partner. */
    public int verifyAccountCalls() {
        return verifyAccountCalls.get();
    }

    private static String lastFour(String account) {
        return account.length() <= 4 ? account : account.substring(account.length() - 4);
    }
}