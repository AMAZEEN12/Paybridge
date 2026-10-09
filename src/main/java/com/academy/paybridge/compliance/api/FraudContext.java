package com.academy.paybridge.compliance.api;

/**
 * Everything the rules need, worked out by the transfer module. Passing facts in (instead of letting
 * compliance read the transfers table) keeps the dependency pointing one way: transfer uses compliance.
 */
public record FraudContext(
        Long customerId,
        String sourceAccount,
        String destinationKey,          // account number, or bankCode:accountNumber for payouts
        long amountKobo,
        long outgoingTodayKobo,         // already sent today, not counting this one
        int recentTransferCount,        // transfers sent in the velocity window, not counting this one
        boolean firstTimeBeneficiary,
        long recentIncomingKobo) {      // money received in the pass-through window
}
