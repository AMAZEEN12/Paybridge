package com.academy.paybridge.remittance.client;

/** A timeout or a server error: the outcome is UNKNOWN. The payout stays PENDING and is re-queried, never refunded (R-PAY-9). */
public class PayoutUnavailableException extends RuntimeException {
    public PayoutUnavailableException(String message) {
        super(message);
    }
}