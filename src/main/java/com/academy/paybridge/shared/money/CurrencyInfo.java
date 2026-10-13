package com.academy.paybridge.shared.money;

/**
 * One row of the currencies table: how a currency is written and how small its smallest unit is.
 * minorUnits is the number of decimal places: 2 for NGN and USD, 0 for JPY, 3 for KWD.
 */
public record CurrencyInfo(String code, String name, String symbol, int minorUnits, boolean enabled) {

    public CurrencyInfo {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("A currency code is three capital letters: " + code);
        }
        if (minorUnits < 0 || minorUnits > 3) {
            throw new IllegalArgumentException("Minor units must be 0 to 3 for " + code + ": " + minorUnits);
        }
        if (name == null || name.isBlank()) {
            name = code;
        }
        if (symbol == null || symbol.isBlank()) {
            symbol = code;
        }
    }
}