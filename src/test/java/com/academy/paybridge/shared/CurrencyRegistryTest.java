package com.academy.paybridge.shared;

import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure tests: the registry is built from a list, so no database is needed. */
class CurrencyRegistryTest {

    // IRR is in the table on purpose: the exclusion list must win. XYZ is switched off.
    private final CurrencyRegistry registry = new CurrencyRegistry(List.of(
            new CurrencyInfo("NGN", "Nigerian naira", "\u20a6", 2, true),
            new CurrencyInfo("USD", "US dollar", "$", 2, true),
            new CurrencyInfo("JPY", "Japanese yen", "\u00a5", 0, true),
            new CurrencyInfo("KWD", "Kuwaiti dinar", "KD", 3, true),
            new CurrencyInfo("IRR", "Iranian rial", "IRR", 2, true),
            new CurrencyInfo("XYZ", "Switched off", "XYZ", 2, false)),
            List.of("irr", " ", "KPW"));

    @Test
    void onlyEnabledNonExcludedCurrenciesAreUsableAndSortedByCode() {
        assertThat(registry.usable()).extracting(CurrencyInfo::code).containsExactly("JPY", "KWD", "NGN", "USD");
    }

    @Test
    void anExcludedCurrencyIsRefusedEvenIfItIsInTheTable() {
        assertThat(registry.find("IRR")).isPresent();                 // the row exists ...
        assertThat(registry.isExcluded("IRR")).isTrue();
        assertThat(registry.isUsable("IRR")).isFalse();                // ... but it can never be used
        assertThatThrownBy(() -> registry.require("IRR")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> registry.parse(new BigDecimal("1"), "irr")).isInstanceOf(ApiException.class);
    }

    @Test
    void unknownDisabledAndMissingCodesAreRefused() {
        assertThatThrownBy(() -> registry.require("XYZ")).isInstanceOf(ApiException.class);   // disabled
        assertThatThrownBy(() -> registry.require("ABC")).isInstanceOf(ApiException.class);   // not in the table
        assertThatThrownBy(() -> registry.require(null)).isInstanceOf(ApiException.class);
        assertThat(registry.isUsable("KPW")).isFalse();
    }

    @Test
    void codesAreCaseInsensitive() {
        assertThat(registry.require(" usd ").code()).isEqualTo("USD");
    }

    @Test
    void amountsUseTheCurrenciesOwnDecimals() {
        assertThat(registry.parse(new BigDecimal("10.50"), "USD").minor()).isEqualTo(1050L);
        assertThat(registry.parse(new BigDecimal("1235"), "JPY").minor()).isEqualTo(1235L);
        assertThat(registry.parse(new BigDecimal("1.250"), "KWD").minor()).isEqualTo(1250L);
        assertThatThrownBy(() -> registry.parse(new BigDecimal("10.5"), "JPY")).isInstanceOf(ApiException.class);
        CurrencyAmount a = registry.amount(1050, "USD");
        assertThat(a.format()).isEqualTo("$10.50");
    }

    @Test
    void formatFallsBackForACurrencyItDoesNotKnow() {
        assertThat(registry.format(1050, "USD")).isEqualTo("$10.50");
        assertThat(registry.format(1050, "ABC")).isEqualTo("1050 ABC");
        assertThat(registry.format(1050, "IRR")).isEqualTo("IRR 10.50");     // an old balance can still be shown
    }
}