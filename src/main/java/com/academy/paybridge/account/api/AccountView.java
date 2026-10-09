package com.academy.paybridge.account.api;

import java.time.Instant;

public record AccountView(String accountNumber, Long customerId, long balanceKobo,
                          AccountType type, AccountStatus status, Instant createdAt) {
}
