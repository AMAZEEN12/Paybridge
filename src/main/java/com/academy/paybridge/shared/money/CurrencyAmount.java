package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount of money in one currency, stored as whole minor units (a long): 1050 NGN-kobo is 10.50 naira.
 * Decimals (BigDecimal) only appear at the edges, through {@link #fromDecimal} and {@link #toDecimal}.
 *
 * Two amounts of different currencies can never be added, subtracted or compared (R-MON-3). Turning one currency
 * into another is a conversion with a rate, and that belongs in the fx module, not here.
 *
 * The older static class {@link Money} stays as the Naira-only helper until step 7 of plan 001.
 */
public record CurrencyAmount(long minor, CurrencyInfo currency) implements Comparable<CurrencyAmount> {

    public CurrencyAmount {
        Objects.requireNonNull(currency, "currency");
    }

    public static CurrencyAmount of(long minor, CurrencyInfo currency) {
        return new CurrencyAmount(minor, currency);
    }

    public static CurrencyAmount zero(CurrencyInfo currency) {
        return new CurrencyAmount(0L, currency);
    }

    /**
     * Reads a decimal from the API edge, for example 5000.50 NGN. Rejects more decimal places than the currency
     * has (JPY has none, KWD has three) and anything that is not greater than zero.
     */
    public static CurrencyAmount fromDecimal(BigDecimal value, CurrencyInfo currency) {
        if (value == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount is required.");
        }
        if (value.stripTrailingZeros().scale() > currency.minorUnits()) {
            throw ApiException.badRequest("INVALID_AMOUNT", currency.minorUnits() == 0
                    ? currency.code() + " amounts cannot have decimal places."
                    : "Amount can have at most " + currency.minorUnits() + " decimal places in " + currency.code() + ".");
        }
        if (value.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        try {
            long minor = value.movePointRight(currency.minorUnits()).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            return new CurrencyAmount(minor, currency);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount is too large.");
        }
    }

    /** The amount as a decimal with exactly the currency's number of decimal places, for example 10.50 or 1000. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor, currency.minorUnits());
    }

    public CurrencyAmount plus(CurrencyAmount other) {
        requireSameCurrency(other);
        return new CurrencyAmount(Math.addExact(minor, other.minor), currency);
    }

    public CurrencyAmount minus(CurrencyAmount other) {
        requireSameCurrency(other);
        return new CurrencyAmount(Math.subtractExact(minor, other.minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    @Override
    public int compareTo(CurrencyAmount other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    /** For messages: the currency's own symbol and decimals, for example "$1,234.50", "JPY 1,235" or "KD 1.250". */
    public String format() {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(currency.minorUnits());
        nf.setMaximumFractionDigits(currency.minorUnits());
        String symbol = currency.symbol();
        boolean letterSymbol = Character.isLetter(symbol.charAt(symbol.length() - 1));
        String number = nf.format(toDecimal().abs());
        return (minor < 0 ? "-" : "") + symbol + (letterSymbol ? " " : "") + number;
    }

    private void requireSameCurrency(CurrencyAmount other) {
        if (!currency.code().equals(other.currency.code())) {
            throw new IllegalArgumentException(
                    "Cannot mix " + currency.code() + " and " + other.currency.code() + " in one calculation.");
        }
    }
}