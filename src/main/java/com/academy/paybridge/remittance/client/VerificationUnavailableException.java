package com.academy.paybridge.remittance.client;

/** The partner could not check the account (unsupported corridor, unavailable, allowance spent). Only this may fall back. */
public class VerificationUnavailableException extends RuntimeException {
    public VerificationUnavailableException(String message) {
        super(message);
    }
}