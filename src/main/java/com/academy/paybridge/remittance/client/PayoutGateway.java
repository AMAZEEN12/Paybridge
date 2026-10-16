package com.academy.paybridge.remittance.client;

/**
 * The one door to the payout partner (Flutterwave). The remittance code only knows this interface. The fake in this
 * package serves tests and local runs; the real client arrives in step B4.
 */
public interface PayoutGateway {

    /**
     * Asks the partner who owns a bank account.
     *
     * @throws AccountNotFoundException        the partner says the account does not exist (never falls back)
     * @throws VerificationUnavailableException the partner cannot answer: unsupported, down or rate limited
     */
    VerifiedName verifyAccount(String country, String bankCode, String accountNumber);

    /**
     * The partner's rate for converting {@code sourceCurrency} into {@code destinationCurrency} (R-PAY-3a).
     *
     * @throws RateUnavailableException no rate can be obtained (unavailable, or the corridor is not supported)
     */
    ProviderRate getRate(String sourceCurrency, String destinationCurrency);

    /**
     * Starts a payout. The reference is OURS, so asking again with the same reference can never pay twice (R-PAY-7).
     *
     * @throws PayoutRejectedException    the partner refused it outright: nothing was sent, refund
     * @throws PayoutUnavailableException a timeout or a 5xx: the outcome is UNKNOWN, so the payout stays PENDING
     */
    PayoutResult initiatePayout(PayoutRequest request);

    /** The partner's record of a payout, by our reference. */
    PayoutResult verifyPayout(String reference);
}