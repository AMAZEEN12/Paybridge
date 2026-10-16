package com.academy.paybridge.compliance.api;

/**
 * Facts for the non-NGN transfer and payout limits (R-LIM2). Amounts are in USD minor units (cents).
 * The caller works out the daily total, so compliance never reads another module's tables (R-LIM2-4).
 */
public record TransferLimitContext(
        Kind kind,
        long amountUsdMinor,
        long dailyUsdMinor) {      // already used today, NOT counting this one

    public enum Kind { TRANSFER, PAYOUT }
}