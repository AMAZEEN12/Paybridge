package com.academy.paybridge.shared.money;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The countries PayBridge knows, loaded from the countries table at start-up (same idea as CurrencyRegistry).
 * A country is only usable for sign-up if its home currency is usable: in the currencies table, enabled and not on
 * the exclusion list. A country that is not in the table at all (for example one whose currency is not supported)
 * is simply unknown.
 */
public final class CountryRegistry {

    private final Map<String, CountryInfo> byCode = new HashMap<>();
    private final CurrencyRegistry currencies;

    public CountryRegistry(Collection<CountryInfo> countries, CurrencyRegistry currencies) {
        this.currencies = currencies;
        for (CountryInfo country : countries) {
            byCode.put(normalise(country.code()), country);
        }
    }

    /** The country's row, even if its currency is not usable. */
    public Optional<CountryInfo> find(String code) {
        return Optional.ofNullable(byCode.get(normalise(code)));
    }

    /** The country, only if customers there can sign up (R-CTY-2). */
    public Optional<CountryInfo> supported(String code) {
        return find(code).filter(c -> currencies.isUsable(c.currencyCode()));
    }

    /** All countries whose home currency is usable and enabled. */
    public List<CountryInfo> allSupported() {
        return byCode.values().stream()
                .filter(c -> currencies.isUsable(c.currencyCode()))
                .sorted(Comparator.comparing(CountryInfo::name))
                .toList();
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
