package com.academy.paybridge.ledger.api;

import java.util.List;

/**
 * The only door other modules may use to write to the ledger.
 *
 * Every method joins the caller's database transaction (it refuses to run without one), so the balance change
 * and its ledger lines are saved together or not at all.
 *
 * Everything is posted through recordJournal (or recordConversion for a conversion). The Naira postings
 * (funding, internal transfer, reserve, payout success, payout refund) are built by {@link NairaJournals}.
 */
public interface LedgerApi {

    // The four outside-world accounts. Real accounts have 10-digit numbers, so these can never collide.
    String EXT_OPENING = "EXT:OPENING";
    String EXT_FUNDING = "EXT:FUNDING";
    String EXT_IN_FLIGHT = "EXT:IN_FLIGHT";
    String EXT_PAID_OUT = "EXT:PAID_OUT";

    /**
     * Posts one journal that may involve several currencies (for example a conversion).
     * Every currency in the journal must balance independently: for each currency,
     * debits must equal credits.
     *
     * A wallet line's account is a wallet key created by {@link #walletKey};
     * its currency must match the key's suffix. The same journal key can be posted only once.
     *
     * @param entryType a short label such as "CONVERSION" (at most 20 characters)
     * @throws IllegalStateException if a line is not positive, a currency is malformed,
     *                               a key and its currency disagree, or any currency does not balance.
     */
    void recordJournal(String journalKey, String reference, String entryType, List<JournalLine> lines);

    /**
     * Records a conversion between two wallets of the same account (spec 7.2).
     * Debits source wallet, credits FX pool and fee in source currency, debits FX pool in target currency,
     * and credits target wallet.
     * Journal key is "CONV:<quoteId>".
     */
    void recordConversion(String quoteId, String accountNumber,
                          String sourceCurrency, String targetCurrency,
                          long sourceMinor, long feeMinor, long netMinor, long targetMinor);

    /**
     * The ledger key of one wallet: "<account_number>:<currency>",
     * for example "2000000001:NGN".
     * EXT: accounts are not wallets and keep their names.
     */
    static String walletKey(String accountNumber, String currency) {
        return accountNumber.startsWith("EXT:") ? accountNumber : accountNumber + ":" + currency;
    }

    /**
     * Drops the currency suffix from a wallet key.
     * Plain account numbers and EXT: names come back unchanged.
     */
    static String accountNumberOf(String key) {
        int colon = key.lastIndexOf(':');
        return (key.startsWith("EXT:") || colon < 0) ? key : key.substring(0, colon);
    }
}