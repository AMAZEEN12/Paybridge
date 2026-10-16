package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.VerificationSource;

/** A recipient account name and where it came from (R-VER-3). {@code label} is what the sender is shown. */
public record VerifiedAccount(String accountName, VerificationSource source, String label,
                              String accountMasked, String country, String currency) {

    public static final String LABEL_VERIFIED = "Name verified";
    public static final String LABEL_SIMULATED = "name not verified (test mode)";

    public boolean verified() {
        return source == VerificationSource.FLUTTERWAVE;
    }
}
