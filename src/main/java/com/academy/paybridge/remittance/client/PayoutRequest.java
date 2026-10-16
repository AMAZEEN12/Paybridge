package com.academy.paybridge.remittance.client;

/**
 * Everything the partner needs for one payout. {@code destinationAmountMinor} is the exact amount the recipient's bank
 * receives (OWN_LOCK, S15). {@code rateRef} is the stored rate id in PROVIDER_LOCK mode, otherwise null.
 * {@code quotedSourceNetMinor} is what PayBridge expects to pay in the source currency, so the partner's real cost
 * can be compared with it (journal J6).
 */
public record PayoutRequest(
        String reference,
        String sourceCurrency,
        String destinationCurrency,
        long destinationAmountMinor,
        String country,
        String bankCode,
        String accountNumber,
        String accountName,
        String rateRef,
        long quotedSourceNetMinor,
        String purpose) {

    @Override
    public String toString() {
        return "PayoutRequest[reference=" + reference + ", " + sourceCurrency + "->" + destinationCurrency
                + ", amount=" + destinationAmountMinor + ", country=" + country + "]";   // never the account number
    }
}