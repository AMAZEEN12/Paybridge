package com.academy.paybridge.remittance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Payout arithmetic: pure numbers, no Spring (R-PAY-4, R-PAY-5). */
public final class PayoutMath {

    private PayoutMath() {
    }

    /** floor(minor x rate), moved from one currency's minor units to another's. */
    public static long convert(long minor, int fromDecimals, int toDecimals, BigDecimal rate) {
        return BigDecimal.valueOf(minor).multiply(rate)
                .movePointRight(toDecimals - fromDecimals)
                .setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** USD cents for an amount; {@code toUsd} is USD per one unit of the currency. Rounded down. */
    public static long usdEquivalent(long minor, int decimals, BigDecimal toUsd) {
        return convert(minor, decimals, 2, toUsd);
    }

    /**
     * R-PAY-4: max(ceil(source x feeRate), ceil(minimumUsd in the source currency)), in source minor units.
     * {@code toUsd} is USD per one unit of the source currency.
     */
    public static long fee(long sourceMinor, int decimals, BigDecimal feeRate, BigDecimal minimumUsd, BigDecimal toUsd) {
        long percentage = BigDecimal.valueOf(sourceMinor).multiply(feeRate)
                .setScale(0, RoundingMode.CEILING).longValueExact();
        long minimum = minimumUsd.divide(toUsd, 12, RoundingMode.CEILING)
                .movePointRight(decimals).setScale(0, RoundingMode.CEILING).longValueExact();
        return Math.max(percentage, minimum);
    }
}