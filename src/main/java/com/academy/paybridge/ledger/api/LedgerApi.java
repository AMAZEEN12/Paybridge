package com.academy.paybridge.ledger.api;

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
}