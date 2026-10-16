package com.academy.paybridge.settlement.api;

/** One sweep that was posted (R-SET-11). */
public record SweepView(long id, String source, String destination, String currency, long amountMinor,
                        String amountShown, String journalKey, String createdBy) {
}