package com.academy.paybridge.settlement.service;

import java.util.Locale;

/**
 * Plan 002-004, step C3 (R-SET-8, R-SET-9). The matching decision for one payout, with no database and no clock.
 * Matching is by our reference and the amount, never by names; the caller pairs the lines by reference.
 */
public final class MatchRules {

    private MatchRules() {
    }

    public enum Result {
        MATCHED,
        /**
         * The partner has not reported it yet and the statement lag has not passed (R-SET-9). Not an exception.
         */
        AWAITING_PARTNER,
        STILL_PENDING,
        MISSING_AT_PARTNER,
        MISSING_ON_OUR_SIDE,
        AMOUNT_DIFFERS,
        STATUS_DIFFERS;

        public boolean isException() {
            return this != MATCHED && this != AWAITING_PARTNER;
        }
    }

    /**
     * Our payout: SUCCESSFUL, FAILED, PENDING or RETRYING.
     */
    public record Ours(long amountMinor, String status) {
    }

    /**
     * The partner's line for the same reference.
     */
    public record Theirs(long amountMinor, String status) {
    }

    public static Result decide(Ours ours, Theirs theirs, boolean lagPassed) {
        // R-SET-9: a payout that is still PENDING or RETRYING is reported, never refunded because a batch closed.
        if (isPending(ours.status())) {
            return Result.STILL_PENDING;
        }
        if (theirs == null) {
            if ("FAILED".equals(ours.status())) {
                return Result.MATCHED;   // nothing was paid out, so nothing to find at the partner
            }
            return lagPassed ? Result.MISSING_AT_PARTNER : Result.AWAITING_PARTNER;
        }
        if (ours.amountMinor() != theirs.amountMinor()) {
            return Result.AMOUNT_DIFFERS;
        }
        String partner = partnerStatus(theirs.status());
        if (!ours.status().equals(partner)) {
            // The partner may simply not have finished yet; that is a late report, not an exception, until the lag passes.
            if ("PENDING".equals(partner) && !lagPassed) {
                return Result.AWAITING_PARTNER;
            }
            return Result.STATUS_DIFFERS;
        }
        return Result.MATCHED;
    }

    static boolean isPending(String ourStatus) {
        return "PENDING".equals(ourStatus) || "RETRYING".equals(ourStatus);
    }

    /**
     * The partner's word to ours: SUCCESS or SUCCESSFUL, FAILED, and anything else counts as still pending.
     */
    static String partnerStatus(String raw) {
        if (raw == null) {
            return "PENDING";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCESSFUL" -> "SUCCESSFUL";
            case "FAILED" -> "FAILED";
            default -> "PENDING";
        };
    }
}
