package com.academy.paybridge.transfer.api;

/**
 * Announced after something happens to a transfer. The notification module listens; the transfer
 * module does not know it exists.
 *
 * For a cross-currency transfer, targetCurrency and targetAmountMinor carry what the recipient gets (R-NTF-5);
 * otherwise they are null and 0. The shorter constructor keeps every earlier publisher unchanged.
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
        boolean flagged,
        String targetCurrency,
        long targetAmountMinor) {

    public TransferEvent(String reference, TransferType type, TransferStage stage, String sourceAccount,
                         String destinationAccount, String destinationBankName, String currency,
                         long amountMinor, long chargesMinor, long totalDebitMinor, boolean flagged) {
        this(reference, type, stage, sourceAccount, destinationAccount, destinationBankName, currency,
                amountMinor, chargesMinor, totalDebitMinor, flagged, null, 0L);
    }
}