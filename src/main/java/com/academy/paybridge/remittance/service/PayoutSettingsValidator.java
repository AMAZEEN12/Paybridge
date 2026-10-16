package com.academy.paybridge.remittance.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Stops the app from starting with a dangerous or unfinished payout setting (R-VER-2), the way the Paystack settings
 * already do. It checks in its constructor, so a bad setting fails the start-up with a clear message.
 */
@Component
public class PayoutSettingsValidator {

    public PayoutSettingsValidator(PayoutProperties props) {
        VerifyMode mode;
        try {
            mode = VerifyMode.parse(props.flutterwave().verifyMode());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("paybridge.payouts.flutterwave." + e.getMessage());
        }

        if (!"fake".equals(props.provider())) {
            throw new IllegalStateException("paybridge.payouts.provider '" + props.provider()
                    + "' is not available yet: the Flutterwave client arrives in step B4. Use 'fake'.");
        }

        String rateMode = props.flutterwave().rateMode();
        if (!rateMode.equals("OWN_LOCK") && !rateMode.equals("PROVIDER_LOCK")) {
            throw new IllegalStateException(
                    "paybridge.payouts.flutterwave.rate-mode must be OWN_LOCK or PROVIDER_LOCK, not '" + rateMode + "'.");
        }

        if (mode != VerifyMode.LIVE && isLiveKey(props.flutterwave().secretKey())) {
            throw new IllegalStateException("verify-mode '" + mode.name().toLowerCase(Locale.ROOT)
                    + "' makes up account names and is only allowed with a Flutterwave TEST key. "
                    + "With a live key, set verify-mode to 'live'.");
        }
    }

    /**
     * A key that is set and does not look like a test key. Flutterwave test keys contain "TEST" (for example
     * FLWSECK_TEST-...). To be checked against the current API reference in step B4.
     */
    static boolean isLiveKey(String key) {
        return key != null && !key.isBlank() && !key.toUpperCase(Locale.ROOT).contains("TEST");
    }
}