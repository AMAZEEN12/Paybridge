package com.academy.paybridge.remittance.client;

/** The partner says the account does not exist. This refuses the quote; it never falls back to a simulated name. */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}