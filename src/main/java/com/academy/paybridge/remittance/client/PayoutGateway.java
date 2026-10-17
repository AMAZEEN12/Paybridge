package com.academy.paybridge.remittance.client;

public interface PayoutGateway {

    VerifiedName verifyAccount(String country, String bankCode, String accountNumber);

    ProviderRate getRate(String sourceCurrency, String destinationCurrency);

    PayoutResult initiatePayout(PayoutRequest request);

    /** Verify a payout using our reference. */
    PayoutResult verifyPayout(String reference);

    /**
     * Verify using the provider's transfer ID when available.
     * Other gateway implementations remain compatible.
     */
    default PayoutResult verifyPayout(String reference, String providerTransferId) {
        return verifyPayout(reference);
    }
}