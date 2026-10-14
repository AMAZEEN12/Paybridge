package com.academy.paybridge.account.api;

/** One wallet of a customer's account, for display. Amounts are in minor units. */
public record WalletView(String accountNumber, String currency, long balanceMinor, String status, boolean home) {
}