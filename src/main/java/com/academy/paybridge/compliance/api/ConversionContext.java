package com.academy.paybridge.compliance.api;

/**
 * Everything the conversion compliance rules need.
 * Amounts are in USD minor units (cents).
 */
public record ConversionContext(
        Long customerId,
        long amountUsdMinor,
        long dailyOutgoingUsdMinor,
        int recentConversionCount) {
}
