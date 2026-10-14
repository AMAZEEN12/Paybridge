package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.domain.FeeReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeeWindowTest {

    private final FeeProperties feeProps = new FeeProperties(new BigDecimal("0.005"), 30);
    private final BigDecimal rate = new BigDecimal("1.0");

    @Test
    void customerAtDay29GetsPromoFee() {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        Instant createdAt = now.minus(Duration.ofDays(29));

        long days = Duration.between(createdAt, now).toDays();
        boolean isPromo = days < feeProps.freeDays();

        assertThat(isPromo).isTrue();

        var calc = QuoteMath.calculate(100_000L, 2, 2, rate, rate, feeProps.rate(), isPromo);
        assertThat(calc.feeMinor()).isZero();
        assertThat(calc.feeReason()).isEqualTo(FeeReason.PROMO);
        assertThat(calc.netMinor()).isEqualTo(100_000L);
    }

    @Test
    void customerAtDay30GetsStandardFee() {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        Instant createdAt = now.minus(Duration.ofDays(30));

        long days = Duration.between(createdAt, now).toDays();
        boolean isPromo = days < feeProps.freeDays();

        assertThat(isPromo).isFalse();

        var calc = QuoteMath.calculate(100_000L, 2, 2, rate, rate, feeProps.rate(), isPromo);
        assertThat(calc.feeMinor()).isEqualTo(500L); // 0.5% of 100,000 = 500
        assertThat(calc.feeReason()).isEqualTo(FeeReason.STANDARD);
        assertThat(calc.netMinor()).isEqualTo(99_500L);
    }

    @Test
    void customerAtDay31GetsStandardFee() {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        Instant createdAt = now.minus(Duration.ofDays(31));

        long days = Duration.between(createdAt, now).toDays();
        boolean isPromo = days < feeProps.freeDays();

        assertThat(isPromo).isFalse();

        var calc = QuoteMath.calculate(100_000L, 2, 2, rate, rate, feeProps.rate(), isPromo);
        assertThat(calc.feeMinor()).isEqualTo(500L);
        assertThat(calc.feeReason()).isEqualTo(FeeReason.STANDARD);
    }
}
