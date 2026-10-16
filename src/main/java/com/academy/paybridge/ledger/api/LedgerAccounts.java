package com.academy.paybridge.ledger.api;

import java.util.regex.Pattern;

/**
 * The names of the ledger-only (outside-world) accounts, in one place (spec S7, R-SET-1).
 * Non-NGN accounts carry the currency as a suffix; the NGN in-flight and paid-out accounts keep their old
 * names until the kobo methods are retired.
 */
public final class LedgerAccounts {

    private static final String NGN = "NGN";

    /** Every outside account family. The currency suffix is optional so the older names stay valid. */
    private static final Pattern KNOWN = Pattern.compile(
            "^EXT:(FX_POOL|FX_FEE|PAYOUT_FEE|RATE_DIFF|REVENUE|IN_FLIGHT|PAID_OUT)(:[A-Z]{3})?$");

    private LedgerAccounts() {
    }

    public static String fxPool(String currency) { return "EXT:FX_POOL:" + currency; }

    public static String fxFee(String currency) { return "EXT:FX_FEE:" + currency; }

    public static String payoutFee(String currency) { return "EXT:PAYOUT_FEE:" + currency; }

    public static String rateDiff(String currency) { return "EXT:RATE_DIFF:" + currency; }

    public static String revenue(String currency) { return "EXT:REVENUE:" + currency; }

    /** VAT and stamp duty swept from the system wallets. Naira only. */
    public static String taxPayable() { return "EXT:TAX_PAYABLE:NGN"; }

    /** Reserved payouts not yet confirmed. NGN keeps the legacy name (S7). */
    public static String inFlight(String currency) {
        return NGN.equals(currency) ? LedgerApi.EXT_IN_FLIGHT : "EXT:IN_FLIGHT:" + currency;
    }

    /** Payouts the partner confirmed as paid. NGN keeps the legacy name (S7). */
    public static String paidOut(String currency) {
        return NGN.equals(currency) ? LedgerApi.EXT_PAID_OUT : "EXT:PAID_OUT:" + currency;
    }

    /** True for an outside account reconciliation knows about (R-LED2-4). */
    public static boolean isKnownExternal(String account) {
        if (account == null) {
            return false;
        }
        return account.equals(LedgerApi.EXT_OPENING)
                || account.equals(LedgerApi.EXT_FUNDING)
                || account.equals(taxPayable())
                || KNOWN.matcher(account).matches();
    }
}