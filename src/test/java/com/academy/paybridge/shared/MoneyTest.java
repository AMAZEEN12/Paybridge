package com.academy.paybridge.shared;

import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void convertsNairaToKoboExactly() {
        assertThat(Money.toKobo(new BigDecimal("5000"))).isEqualTo(500_000L);
        assertThat(Money.toKobo(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(Money.toKobo(new BigDecimal("1234.50"))).isEqualTo(123_450L);
    }

    @Test
    void rejectsMoreThanTwoDecimalsAndNonPositiveAmounts() {
        assertThatThrownBy(() -> Money.toKobo(new BigDecimal("1.234"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.toKobo(BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.toKobo(new BigDecimal("-5"))).isInstanceOf(ApiException.class);
    }

    @Test
    void formatsAndMasks() {
        assertThat(Money.toNaira(2_005_000L)).isEqualByComparingTo("20050.00");
        assertThat(Money.format(2_005_000L)).isEqualTo("₦20,050.00");
        assertThat(Money.maskAccount("2000001234")).isEqualTo("****1234");
    }
}
