package com.academy.paybridge.shared;

import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure tests: no database, no Spring. The three hard cases: 0, 2 and 3 decimal places. */
class CurrencyAmountTest {

    static final CurrencyInfo NGN = new CurrencyInfo("NGN", "Nigerian naira", "\u20a6", 2, true);
    static final CurrencyInfo USD = new CurrencyInfo("USD", "US dollar", "$", 2, true);
    static final CurrencyInfo JPY = new CurrencyInfo("JPY", "Japanese yen", "\u00a5", 0, true);
    static final CurrencyInfo KWD = new CurrencyInfo("KWD", "Kuwaiti dinar", "KD", 3, true);

    @Test
    void twoDecimalCurrencyKeepsCents() {
        CurrencyAmount a = CurrencyAmount.fromDecimal(new BigDecimal("10.50"), USD);

        assertThat(a.minor()).isEqualTo(1050L);
        assertThat(a.toDecimal()).isEqualByComparingTo("10.50");
        assertThat(a.toDecimal().scale()).isEqualTo(2);
        assertThat(a.format()).isEqualTo("$10.50");
        assertThat(CurrencyAmount.fromDecimal(new BigDecimal("10.500"), USD).minor()).isEqualTo(1050L);   // trailing zero is fine
    }

    @Test
    void nairaFormatsWithItsOwnSymbol() {
        assertThat(CurrencyAmount.of(500_000L, NGN).format()).isEqualTo("\u20a65,000.00");
    }

    @Test
    void zeroDecimalCurrencyHasNoDecimals() {
        CurrencyAmount a = CurrencyAmount.fromDecimal(new BigDecimal("1235"), JPY);

        assertThat(a.minor()).isEqualTo(1235L);
        assertThat(a.toDecimal().scale()).isZero();
        assertThat(a.format()).isEqualTo("\u00a51,235");
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(new BigDecimal("12.5"), JPY)).isInstanceOf(ApiException.class);
    }

    @Test
    void threeDecimalCurrencyKeepsThreePlaces() {
        CurrencyAmount a = CurrencyAmount.fromDecimal(new BigDecimal("1.250"), KWD);

        assertThat(a.minor()).isEqualTo(1250L);
        assertThat(a.toDecimal().scale()).isEqualTo(3);
        assertThat(a.format()).isEqualTo("KD 1.250");                       // a letter symbol gets a space
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(new BigDecimal("0.0005"), KWD)).isInstanceOf(ApiException.class);
    }

    @Test
    void tooManyDecimalPlacesAreRefusedNotRounded() {
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(new BigDecimal("10.505"), USD)).isInstanceOf(ApiException.class);
    }

    @Test
    void zeroNegativeMissingAndHugeAmountsAreRefused() {
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(BigDecimal.ZERO, USD)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(new BigDecimal("-1"), USD)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(null, USD)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CurrencyAmount.fromDecimal(new BigDecimal("999999999999999999"), USD))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sameCurrencyAddsSubtractsAndCompares() {
        CurrencyAmount ten = CurrencyAmount.of(1000, USD);
        CurrencyAmount three = CurrencyAmount.of(300, USD);

        assertThat(ten.plus(three).minor()).isEqualTo(1300L);
        assertThat(ten.minus(three).minor()).isEqualTo(700L);
        assertThat(ten.compareTo(three)).isPositive();
        assertThat(three.minus(ten).isNegative()).isTrue();
        assertThat(three.minus(ten).format()).isEqualTo("-$7.00");
        assertThat(CurrencyAmount.zero(USD).isZero()).isTrue();
    }

    @Test
    void mixingCurrenciesIsAnError() {
        CurrencyAmount usd = CurrencyAmount.of(1000, USD);
        CurrencyAmount ngn = CurrencyAmount.of(1000, NGN);

        assertThatThrownBy(() -> usd.plus(ngn)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot mix");
        assertThatThrownBy(() -> usd.minus(ngn)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usd.compareTo(ngn)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCurrencyNeedsAValidCodeAndMinorUnits() {
        assertThatThrownBy(() -> new CurrencyInfo("usd", "x", "$", 2, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyInfo("USD", "x", "$", 4, true)).isInstanceOf(IllegalArgumentException.class);
    }
}