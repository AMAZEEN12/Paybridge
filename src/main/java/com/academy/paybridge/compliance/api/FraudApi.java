package com.academy.paybridge.compliance.api;

/** The only door other modules may use to talk to the compliance module. */
public interface FraudApi {

    FraudResult evaluate(FraudContext context);

    /** How far back the "too many transfers" rule looks, so the transfer module can gather the right facts. */
    int velocityWindowMinutes();

    /** How far back the pass-through rule looks for incoming money. */
    int passThroughWindowMinutes();

    /** Saves a BLOCK or FLAG decision in its own transaction, so it survives a rollback of the transfer. */
    void record(String reference, String accountNumber, FraudResult result);
}
