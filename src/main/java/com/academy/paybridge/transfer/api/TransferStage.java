package com.academy.paybridge.transfer.api;

/** Which moment of a transfer's life an event describes. */
public enum TransferStage {
    COMPLETED,   // internal transfer finished
    ACCEPTED,    // external payout debited and sent, outcome not known yet
    SUCCEEDED,   // external payout confirmed
    FAILED,      // external payout failed and the money was returned
    NOT_SENT     // a confirmed cross-currency transfer failed; no money moved (R-NTF-2, R-NTF-5)
}