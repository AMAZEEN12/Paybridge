package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.ApiException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The currencies PayBridge knows. A plain class built from a list, so unit tests need no database; the Spring bean
 * that fills it from the currencies table is {@code CurrencyConfig}.
 *
 * A currency is USABLE when it is in the table, enabled, and not on the exclusion list (sanctioned currencies,
 * kept in configuration). An excluded currency stays unusable even if somebody adds it to the table (R-CUR-2).
 */
public final class CurrencyRegistry {

    private final Map<String, CurrencyInfo> byCode = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();

    public CurrencyRegistry(Collection<CurrencyInfo> currencies, Collection<String> excludedCodes) {
        for (CurrencyInfo info : currencies) {
            byCode.put(info.code(), info);
        }
        for (String code : excludedCodes) {
            String normal = normalise(code);
            if (normal != null && !normal.isEmpty()) {
                excluded.add(normal);
            }
        }
    }

    /** The currency's row, even if it is excluded or disabled. For display of existing balances. */
    public Optional<CurrencyInfo> find(String code) {
        return Optional.ofNullable(byCode.get(normalise(code)));
    }

    public boolean isExcluded(String code) {
        return excluded.contains(normalise(code));
    }

    /** True only for a currency that is in the table, enabled and not excluded. */
    public boolean isUsable(String code) {
        CurrencyInfo info = byCode.get(normalise(code));
        return info != null && info.enabled() && !excluded.contains(info.code());
    }

    /** The currency for a new wallet, swap or conversion. Refuses unknown, disabled and excluded currencies alike. */
    public CurrencyInfo require(String code) {
        if (!isUsable(code)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "This currency is not supported.");
        }
        return byCode.get(normalise(code));
    }

    /** Every usable currency, sorted by code. */
    public List<CurrencyInfo> usable() {
        return byCode.values().stream()
                .filter(c -> c.enabled() && !excluded.contains(c.code()))
                .sorted(Comparator.comparing(CurrencyInfo::code))
                .toList();
    }

    public CurrencyAmount amount(long minor, String code) {
        return CurrencyAmount.of(minor, require(code));
    }

    /** An amount typed at the API edge, for example 10.50 USD. Refuses an unusable currency. */
    public CurrencyAmount parse(BigDecimal value, String code) {
        return CurrencyAmount.fromDecimal(value, require(code));
    }

    /** For messages about balances that already exist. Falls back to the plain number and the code if unknown. */
    public String format(long minor, String code) {
        return find(code).map(info -> CurrencyAmount.of(minor, info).format()).orElse(minor + " " + code);
    }

    private static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}