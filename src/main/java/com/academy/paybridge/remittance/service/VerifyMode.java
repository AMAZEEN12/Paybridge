package com.academy.paybridge.remittance.service;

import java.util.Locale;

/** paybridge.payouts.flutterwave.verify-mode (R-VER-2). */
public enum VerifyMode {
    LIVE,         // the partner answers; any failure refuses the quote
    AUTO,         // the partner answers where it can; otherwise a labelled simulated answer (test key only)
    SIMULATED;    // always simulated (test key only)

    public static VerifyMode parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "live" -> LIVE;
            case "auto" -> AUTO;
            case "simulated" -> SIMULATED;
            default -> throw new IllegalArgumentException(
                    "verify-mode must be live, auto or simulated, not '" + value + "'.");
        };
    }
}