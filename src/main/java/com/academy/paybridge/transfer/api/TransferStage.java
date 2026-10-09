package com.academy.paybridge.transfer.api;

/** Which moment of a transfer's life an event describes. */
public enum TransferStage {
    COMPLETED,   // internal transfer finished
    ACCEPTED,    // external payout debited and sent, outcome not known yet
    SUCCEEDED,   // external payout confirmed
    FAILED       // external payout failed and the money was returned
}
