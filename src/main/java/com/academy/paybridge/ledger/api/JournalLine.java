package com.academy.paybridge.ledger.api;

/**
 /* One line of a journal posted through {ink LedgerApirecordJournal}.
 *
 * @param account     the ledger key: a wallet key from {link LedgerApi#walletKey} (for example "2000000001:USD"),
 *                    or an outside account such as "EXT:FUNDING" or "EXT:FX_POOL:USD"
 * @param side        DEBIT or CREDIT
 * @param currency    the currency of this line, three capital letters
 * @param amountMinor positive whole minor units of that currency
 */
public record JournalLine(String account, Side side, String currency, long amountMinor) {

    public enum Side { DEBIT, CREDIT }
}