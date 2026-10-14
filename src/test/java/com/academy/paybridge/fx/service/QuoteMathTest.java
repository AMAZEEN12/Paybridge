package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.domain.FeeReason;
import com.academy.paybridge.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteMathTest {

    private final BigDecimal feeRate = new BigDecimal("0.005"); // 0.5%
    private final BigDecimal rateNgnToUsd = new BigDecimal("0.000666666667"); // 1/1500
    private final BigDecimal rateNgnToJpy = new BigDecimal("0.1"); // 150/1500 = 0.1
    private final BigDecimal rateNgnToKwd = new BigDecimal("0.000204666667"); // 0.307/1500

    @Test
    void promoGivesZeroFeeWithPromoReason() {
        var calc = QuoteMath.calculate(1_000_000L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, true);
        assertThat(calc.feeMinor()).isZero();
        assertThat(calc.feeReason()).isEqualTo(FeeReason.PROMO);
        assertThat(calc.netMinor()).isEqualTo(1_000_000L);
        assertThat(calc.targetMinor()).isEqualTo(666L); // $6.66
        assertThat(calc.usdEquivalentMinor()).isEqualTo(666L);
    }

    @Test
    void standardFeeCeilsFractionalMinorUnits() {
        // 0.5% of 100 minor units is 0.5 -> ceils to 1
        var calc = QuoteMath.calculate(100L, 2, 2, new BigDecimal("1.0"), BigDecimal.ONE, feeRate, false);
        assertThat(calc.feeMinor()).isEqualTo(1L);
        assertThat(calc.feeReason()).isEqualTo(FeeReason.STANDARD);
        assertThat(calc.netMinor()).isEqualTo(99L);
    }

    @Test
    void standardFeeOnLargeAmount() {
        // 0.5% of 1,000,000 kobo (10,000 NGN) is 5,000 kobo (50 NGN)
        var calc = QuoteMath.calculate(1_000_000L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, false);
        assertThat(calc.feeMinor()).isEqualTo(5_000L);
        assertThat(calc.feeReason()).isEqualTo(FeeReason.STANDARD);
        assertThat(calc.netMinor()).isEqualTo(995_000L);
        assertThat(calc.targetMinor()).isEqualTo(663L); // $6.63
    }

    @Test
    void targetPayoutFloorsFractionalMinorUnits() {
        // net 995,000 * 0.000666666667 = 663.333333665 -> floors to 663
        var calc = QuoteMath.calculate(1_000_000L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, false);
        assertThat(calc.targetMinor()).isEqualTo(663L);
    }

    @Test
    void zeroDecimalTargetCurrencyFloorsCorrectly() {
        // NGN (2 decimals) to JPY (0 decimals). Net: 1,000,000 kobo = 10,000 NGN.
        // Rate: 0.1 JPY per NGN. Factor: 10^(0 - 2) = 0.01.
        // target: 1,000,000 * 0.1 * 0.01 = 1,000 JPY.
        var calc = QuoteMath.calculate(1_000_000L, 2, 0, rateNgnToJpy, rateNgnToUsd, feeRate, true);
        assertThat(calc.targetMinor()).isEqualTo(1_000L);
    }

    @Test
    void threeDecimalTargetCurrencyFloorsCorrectly() {
        // NGN (2 decimals) to KWD (3 decimals).
        // 1,000,000 kobo. Factor: 10^(3 - 2) = 10.
        // Rate: 0.000204666667. 1,000,000 * 0.000204666667 * 10 = 2046.66667 -> 2046 minor units (2.046 KWD).
        var calc = QuoteMath.calculate(1_000_000L, 2, 3, rateNgnToKwd, rateNgnToUsd, feeRate, true);
        assertThat(calc.targetMinor()).isEqualTo(2046L);
    }

    @Test
    void zeroTargetAmountIsRefused() {
        // 1 kobo converting to USD at 1/1500 produces 0 cents -> must be rejected
        assertThatThrownBy(() -> QuoteMath.calculate(1L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void nonPositiveAmountIsRefused() {
        assertThatThrownBy(() -> QuoteMath.calculate(0L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, true))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QuoteMath.calculate(-100L, 2, 2, rateNgnToUsd, rateNgnToUsd, feeRate, true))
                .isInstanceOf(ApiException.class);
    }
}
