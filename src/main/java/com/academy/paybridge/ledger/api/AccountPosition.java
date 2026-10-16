package com.academy.paybridge.ledger.api;

/** One ledger account in one currency: credits minus debits, in that currency's minor units (R-SET-1). */
public record AccountPosition(String account, String currency, long netMinor) {
}