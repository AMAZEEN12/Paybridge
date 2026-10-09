package com.academy.paybridge.account.api;

/** Published when a customer freezes or unfreezes an account, so other modules (notifications) can react. */
public record AccountStatusChangedEvent(String accountNumber, Long customerId, AccountStatus status) {
}
