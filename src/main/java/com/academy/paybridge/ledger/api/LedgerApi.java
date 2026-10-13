package com.academy.paybridge.ledger.api;

import java.util.List;

/**
 * The only door other modules may use to write to the ledger.
 *
 * Every method joins the caller's database transaction (it refuses to run without one), so the balance change
 * and its ledger lines are saved together or not at all.
 */
public interface LedgerApi {

    // The four outside-world accounts. Real accounts have 10-digit numbers, so these can never collide.
    String EXT_OPENING = "EXT:OPENING";
    String EXT_FUNDING = "EXT:FUNDING";
    String EXT_IN_FLIGHT = "EXT:IN_FLIGHT";
    String EXT_PAID_OUT = "EXT:PAID_OUT";

    /** Money added from outside (development funding). */
    void recordFunding(String accountNumber, long kobo);

    /** Internal transfer: source pays amount + charges; destination and the charge accounts are credited. */
    void recordInternal(String reference, String source, String destination,
                        long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo);

    /** Payout reserved: the customer is debited and the money waits in EXT:IN_FLIGHT. */
    void recordReserve(String reference, String source, long totalDebitKobo);

    /** Payout confirmed: EXT:IN_FLIGHT is released to EXT:PAID_OUT and the charge accounts. */
    void recordPayoutSuccess(String reference, long amountKobo, long feeKobo, long vatKobo, long stampDutyKobo);

    /** Payout failed: EXT:IN_FLIGHT goes back to the customer, charges included. */
    void recordPayoutRefund(String reference, String source, long totalDebitKobo);

    /**
     * Posts one journal that may involve several currencies (for example a conversion, plan step 6). Every currency
     * in the journal must balance ON ITS OWN: for each currency, debits equal credits (R-LED-3).
     *
     * A wallet line's account is {@link #walletKey}; its currency must match the key's suffix. The same journal key
     * can be posted only once (the database refuses a repeat).
     *
     * @param entryType a short label such as "CONVERSION" (at most 20 characters)
     * @throws IllegalStateException if a line is not positive, a currency is malformed, a key and its currency
     *                               disagree, or any currency does not balance. Nothing is saved in that case.
     */
    void recordJournal(String journalKey, String reference, String entryType, List<JournalLine> lines);

    /**
     * The ledger key of one wallet: "<account_number>:<currency>", for example "2000000001:NGN".
     * The EXT: accounts are not wallets and keep their names.
     */
    static String walletKey(String accountNumber, String currency) {
        return accountNumber.startsWith("EXT:") ? accountNumber : accountNumber + ":" + currency;
    }

    /** The reverse, for messages: drops the ":NGN" suffix. Plain account numbers and EXT: names come back unchanged. */
    static String accountNumberOf(String key) {
        int colon = key.lastIndexOf(':');
        return (key.startsWith("EXT:") || colon < 0) ? key : key.substring(0, colon);
    }
}