package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Money is stored and calculated in kobo (a long). Naira with decimals only exists at the
 * edges of the API. Never use double for money.
 */
public final class Money {

    private Money() {
    }

    /** Converts naira (for example 5000.50) to kobo. Rejects more than 2 decimal places. */
    public static long toKobo(BigDecimal naira) {
        if (naira == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount is required.");
        }
        BigDecimal stripped = naira.stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount can have at most 2 decimal places.");
        }
        if (naira.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        try {
            return naira.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount is too large.");
        }
    }

    /** Converts kobo to a naira BigDecimal with exactly 2 decimals. */
    public static BigDecimal toNaira(long kobo) {
        return BigDecimal.valueOf(kobo, 2);
    }

    /** For messages, for example "₦5,000.00". */
    public static String format(long kobo) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₦" + nf.format(toNaira(kobo));
    }

    /** Shows only the last four digits of an account number. */
    public static String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
