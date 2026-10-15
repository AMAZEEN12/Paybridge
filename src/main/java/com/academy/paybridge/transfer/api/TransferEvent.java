package com.academy.paybridge.transfer.api;

/**
 * Announced after something happens to a transfer. The notification module listens; the transfer
 * module does not know it exists.
 */
public record TransferEvent(
        String reference,
        TransferType type,
        TransferStage stage,
        String sourceAccount,
        String destinationAccount,
        String destinationBankName,
        String currency,
        long amountMinor,
        long chargesMinor,
        long totalDebitMinor,
        boolean flagged) {
}
