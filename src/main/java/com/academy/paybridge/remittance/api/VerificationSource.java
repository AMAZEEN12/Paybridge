package com.academy.paybridge.remittance.api;

/** Where a recipient's account name came from (R-VER-3). A SIMULATED name is never shown or recorded as real. */
public enum VerificationSource {
    FLUTTERWAVE,
    SIMULATED
}