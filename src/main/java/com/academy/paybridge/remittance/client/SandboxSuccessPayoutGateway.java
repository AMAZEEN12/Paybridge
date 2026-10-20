package com.academy.paybridge.remittance.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEST-KEY ONLY. Flutterwave's sandbox leaves many payouts (cross-currency ones above all) in NEW or PENDING for
 * ever and sends no webhook. This wrapper sits in front of the real Flutterwave client and, once a payout has been
 * waiting for FLUTTERWAVE_SIMULATE_SUCCESS_AFTER (for example PT15S), reports it as SUCCESS so the rest of PayBridge
 * (settlement, ledger journals, notifications) runs exactly as it would for a real success.
 *
 * It is switched on only when BOTH are true: the provider is "flutterwave" and FLUTTERWAVE_SIMULATE_SUCCESS_AFTER
 * is set. It refuses to start with a live secret key. Everything else (name lookup, rates, the initiate call) goes
 * to the real client untouched, so a live name lookup works as usual and the payout it leads to is simulated too.
 *
 *  - A real SUCCESS or FAILED from Flutterwave always wins; only "still pending" is overridden.
 *  - While waiting, "Flutterwave has no record" is answered as PENDING, so the settlement service does not send
 *    the same payout again and again.
 *  - The timer lives in memory. After a restart (Render waking up) a payout Flutterwave still lists as pending is
 *    treated as already waiting long enough.
 */
@Component
@Primary
@ConditionalOnExpression("'${paybridge.payouts.provider:fake}'.equalsIgnoreCase('flutterwave') "
        + "and !'${FLUTTERWAVE_SIMULATE_SUCCESS_AFTER:}'.trim().isEmpty()")
public class SandboxSuccessPayoutGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxSuccessPayoutGateway.class);

    private final PayoutGateway delegate;
    private final Clock clock;
    private final Duration after;
    private final Map<String, Instant> sentAt = new ConcurrentHashMap<>();

    @Autowired
    public SandboxSuccessPayoutGateway(FlutterwavePayoutGateway delegate,
                                       com.academy.paybridge.remittance.service.PayoutProperties props,
                                       Clock clock,
                                       @Value("${FLUTTERWAVE_SIMULATE_SUCCESS_AFTER}") String after) {
        this(delegate, props.flutterwave().secretKey(), clock, parse(after));
    }

    /** Also used by a unit test, with a fake partner. */
    SandboxSuccessPayoutGateway(PayoutGateway delegate, String secretKey, Clock clock, Duration after) {
        if (secretKey == null || !secretKey.startsWith("FLWSECK_TEST")) {
            throw new IllegalStateException(
                    "FLUTTERWAVE_SIMULATE_SUCCESS_AFTER is for a Flutterwave TEST key only. "
                            + "Remove it before using a live key.");
        }
        this.delegate = delegate;
        this.clock = clock;
        this.after = after;
        log.warn("PAYOUTS: SANDBOX SIMULATION is ON. A payout still pending after {} will be reported as paid. "
                + "Test key only.", after);
    }

    @Override
    public VerifiedName verifyAccount(String country, String bankCode, String accountNumber) {
        return delegate.verifyAccount(country, bankCode, accountNumber);
    }

    @Override
    public ProviderRate getRate(String sourceCurrency, String destinationCurrency) {
        return delegate.getRate(sourceCurrency, destinationCurrency);
    }

    @Override
    public PayoutResult initiatePayout(PayoutRequest request) {
        // putIfAbsent: sending the same reference again must not restart the clock.
        sentAt.putIfAbsent(request.reference(), clock.instant());
        try {
            return delegate.initiatePayout(request);
        } catch (PayoutRejectedException e) {
            sentAt.remove(request.reference());                    // refused outright: nothing is waiting
            throw e;
        }
    }

    @Override
    public PayoutResult verifyPayout(String reference) {
        PayoutResult real = null;
        PayoutUnavailableException unreachable = null;
        try {
            real = delegate.verifyPayout(reference);
        } catch (PayoutUnavailableException e) {
            unreachable = e;
        }

        if (real != null && (real.status() == PayoutStatus.SUCCESS || real.status() == PayoutStatus.FAILED)) {
            sentAt.remove(reference);
            return real;                                           // the partner decided: believe it
        }

        Instant sent = sentAt.get(reference);
        boolean partnerHasIt = real != null && real.status() == PayoutStatus.PENDING;
        if (sent == null && !partnerHasIt) {
            // We never sent it (or lost the timer) and the partner does not list it: behave as the real client does.
            if (unreachable != null) {
                throw unreachable;
            }
            return real;
        }

        boolean due = sent == null || !clock.instant().isBefore(sent.plus(after));
        if (!due) {
            return PayoutResult.of(PayoutStatus.PENDING, real == null ? null : real.providerRef(),
                    "Sandbox: waiting " + after.toSeconds() + "s before marking this payout paid");
        }
        sentAt.remove(reference);
        String providerRef = real != null && real.providerRef() != null ? real.providerRef() : "sim_" + reference;
        log.warn("PAYOUTS: sandbox simulation marked {} as paid", reference);
        return PayoutResult.of(PayoutStatus.SUCCESS, providerRef, "Sandbox simulation: marked paid");
    }

    private static Duration parse(String value) {
        String v = value.trim();
        return v.matches("\\d+") ? Duration.ofSeconds(Long.parseLong(v)) : Duration.parse(v);   // "15" or "PT15S"
    }
}