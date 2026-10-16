package com.academy.paybridge.remittance.client;

/** No partner rate can be obtained. The payout quote is refused: it never falls back to the mid-market rate (R-PAY-3b). */
public class RateUnavailableException extends RuntimeException {
    public RateUnavailableException(String message) {
        super(message);
    }
}