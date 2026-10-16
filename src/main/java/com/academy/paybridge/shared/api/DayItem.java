package com.academy.paybridge.shared.api;

/** One payout, transfer or conversion as the settlement module sees it. Kind is PAYOUT, TRANSFER or CONVERSION. */
public record DayItem(String reference, String kind, String currency, long amountMinor, String status) {
}