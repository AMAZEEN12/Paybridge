package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.domain.FeeReason;
import com.academy.paybridge.shared.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure calculation helper for quotes (R-CONV-2, R-CONV-3, R-MON-4, R-MON-5).
 * All conversion arithmetic goes through this helper.
 */
public final class QuoteMath {

    private QuoteMath() {
    }

    public record QuoteCalculation(
            long feeMinor,
            FeeReason feeReason,
            long netMinor,
            long targetMinor,
            long usdEquivalentMinor) {
    }

    /**
     * Calculates fee, net, target amount, and USD equivalent in accordance with R-CONV-2 and R-MON-5.
     *
     * 1. fee = ceil(source_amount x fee_rate) in source currency minor units, or 0 during free promo.
     * 2. net = source_amount - fee.
     * 3. target = floor(net x rate x 10^(target_decimals - source_decimals)) in target currency minor units.
     */
    public static QuoteCalculation calculate(
            long sourceAmountMinor,
            int sourceDecimals,
            int targetDecimals,
            BigDecimal rate,
            BigDecimal rateToUsd,
            BigDecimal feeRate,
            boolean isPromo) {

        if (sourceAmountMinor <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Source amount must be greater than zero.");
        }

        // 1. Fee
        long feeMinor;
        FeeReason feeReason;
        if (isPromo || feeRate == null || feeRate.signum() <= 0) {
            feeMinor = 0L;
            feeReason = FeeReason.PROMO;
        } else {
            feeMinor = BigDecimal.valueOf(sourceAmountMinor)
                    .multiply(feeRate)
                    .setScale(0, RoundingMode.CEILING)
                    .longValueExact();
            feeReason = FeeReason.STANDARD;
        }

        // 2. Net
        long netMinor = Math.subtractExact(sourceAmountMinor, feeMinor);
        if (netMinor <= 0) {
            throw ApiException.unprocessable("AMOUNT_TOO_SMALL", "The amount is too small after deducting fees.");
        }

        // 3. Target
        long targetMinor = convertAmount(netMinor, sourceDecimals, targetDecimals, rate);
        if (targetMinor <= 0) {
            throw ApiException.unprocessable("CONVERSION_TOO_SMALL",
                    "The amount to convert produces zero after rounding.");
        }

        // 4. USD equivalent for limits
        long usdEquivalentMinor = convertAmount(sourceAmountMinor, sourceDecimals, 2, rateToUsd);

        return new QuoteCalculation(feeMinor, feeReason, netMinor, targetMinor, usdEquivalentMinor);
    }

    /**
     * Converts a minor amount using rate and decimal adjustment:
     * floor(amount x rate x 10^(targetDecimals - sourceDecimals)).
     */
    public static long convertAmount(long amountMinor, int sourceDecimals, int targetDecimals, BigDecimal rate) {
        if (amountMinor <= 0) {
            return 0L;
        }
        int exponent = targetDecimals - sourceDecimals;
        BigDecimal val = BigDecimal.valueOf(amountMinor).multiply(rate);
        if (exponent > 0) {
            BigDecimal factor = BigDecimal.TEN.pow(exponent);
            val = val.multiply(factor);
        } else if (exponent < 0) {
            BigDecimal factor = BigDecimal.TEN.pow(-exponent);
            val = val.divide(factor, 20, RoundingMode.DOWN);
        }
        return val.setScale(0, RoundingMode.FLOOR).longValueExact();
    }
}
