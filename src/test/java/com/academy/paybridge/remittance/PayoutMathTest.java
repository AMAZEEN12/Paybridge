package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.service.PayoutMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** R-PAY-4 and R-PAY-5: the fee and the exact amount, with no Spring and no database. */
class PayoutMathTest {

    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;

    @Test
    void theFeeIsOnePercentWhenThatIsMoreThanOneDollar() {
        // 500.00 USD: 1% is 5.00, more than the $1 minimum
        assertThat(PayoutMath.fee(50_000, 2, ONE_PERCENT, ONE_DOLLAR, BigDecimal.ONE)).isEqualTo(500);
    }

    @Test
    void theFeeIsTheDollarMinimumWhenOnePercentIsLess() {
        // 20.00 USD: 1% is 0.20, so the $1.00 minimum applies
        assertThat(PayoutMath.fee(2_000, 2, ONE_PERCENT, ONE_DOLLAR, BigDecimal.ONE)).isEqualTo(100);
    }

    @Test
    void theFeeRoundsUpNeverDown() {
        // 10.01 USD: 1% is 0.1001, but the minimum is 1.00; and 100,001 cents x 1% = 1,000.01, rounds up to 1,001
        assertThat(PayoutMath.fee(100_001, 2, ONE_PERCENT, ONE_DOLLAR, BigDecimal.ONE)).isEqualTo(1_001);
    }

    @Test
    void theDollarMinimumInAnotherCurrencyRoundsUp() {
        // NGN, 0.00066 USD per naira: $1 is 1,515.15 naira = 151,515.15 kobo, rounded up to 151,516
        assertThat(PayoutMath.fee(1_000_000, 2, ONE_PERCENT, ONE_DOLLAR, new BigDecimal("0.00066"))).isEqualTo(151_516);
    }

    @Test
    void theDollarMinimumInAZeroDecimalCurrency() {
        // JPY, 0.0067 USD per yen: $1 is 149.25 yen, rounded up to 150 (no minor units)
        assertThat(PayoutMath.fee(10_000, 0, ONE_PERCENT, ONE_DOLLAR, new BigDecimal("0.0067"))).isEqualTo(150);
    }


    @Test
    void convertingRoundsDownSoTheRecipientNeverGetsMoreThanPromised() {
        // 99.00 USD at 1515.1515 = 149,999.9985 NGN.
        // In kobo, that is 14,999,999.85, which rounds down to 14,999,999.
        assertThat(PayoutMath.convert(
                9_900,
                2,
                2,
                new BigDecimal("1515.1515")
        )).isEqualTo(14_999_999);
    }


    @Test
    void convertingBetweenDifferentDecimalPlaces() {
        // 100.00 USD at 150 JPY per dollar: 15,000 yen, which has no minor units
        assertThat(PayoutMath.convert(10_000, 2, 0, new BigDecimal("150"))).isEqualTo(15_000);
        // 15,000 JPY at 0.0067 USD per yen: 100.50 USD = 10,050 cents
        assertThat(PayoutMath.convert(15_000, 0, 2, new BigDecimal("0.0067"))).isEqualTo(10_050);
    }

    @Test
    void theUsdEquivalentRoundsDownToTheCent() {
        // 1,000.00 GBP at 1.2799 USD per pound: 1,279.90 USD
        assertThat(PayoutMath.usdEquivalent(100_000, 2, new BigDecimal("1.2799"))).isEqualTo(127_990);
        // 0.01 USD at 1.0 is exactly one cent
        assertThat(PayoutMath.usdEquivalent(1, 2, BigDecimal.ONE)).isEqualTo(1);
    }
}