package com.academy.paybridge.compliance.api;

/** The door the transfer and remittance modules use for the USD-equivalent limits (R-LIM2-2, R-LIM2-4). */
public interface TransferLimitApi {

    /** ALLOW, or BLOCK with the reasons. Conversions have their own limits and their own total. */
    FraudResult evaluate(TransferLimitContext context);
}