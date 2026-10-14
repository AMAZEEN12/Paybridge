package com.academy.paybridge.shared;

import com.academy.paybridge.shared.money.CountryInfo;
import com.academy.paybridge.shared.money.CountryRegistry;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CountryRegistryTest {

    private final CurrencyRegistry currencies = new CurrencyRegistry(List.of(
            new CurrencyInfo("NGN", "Naira", "N", 2, true),
            new CurrencyInfo("USD", "US dollar", "$", 2, true),
            new CurrencyInfo("EUR", "Euro", "E", 2, false),          // disabled
            new CurrencyInfo("IRR", "Rial", "R", 2, true)), List.of("IRR"));   // excluded

    private final CountryRegistry countries = new CountryRegistry(List.of(
            new CountryInfo("NG", "Nigeria", "NGN"),
            new CountryInfo("US", "United States", "USD"),
            new CountryInfo("IE", "Ireland", "EUR"),
            new CountryInfo("IR", "Iran", "IRR")), currencies);

    @Test
    void aCountryWithAUsableCurrencyIsSupportedWhateverTheCase() {
        assertThat(countries.supported("NG")).isPresent();
        assertThat(countries.supported(" us ")).isPresent();
    }

    @Test
    void aCountryWhoseCurrencyIsExcludedOrDisabledIsKnownButNotSupported() {
        assertThat(countries.find("IR")).isPresent();
        assertThat(countries.supported("IR")).isEmpty();
        assertThat(countries.supported("IE")).isEmpty();
    }

    @Test
    void anUnknownOrMissingCountryIsNotSupported() {
        assertThat(countries.supported("ZZ")).isEmpty();
        assertThat(countries.supported(null)).isEmpty();
    }
}