package com.academy.paybridge.remittance.client;

/** The partner refused the payout outright. Nothing was sent, so the customer is refunded at once (journal J4). */
public class PayoutRejectedException extends RuntimeException {
    public PayoutRejectedException(String message) {
        super(message);
    }
}