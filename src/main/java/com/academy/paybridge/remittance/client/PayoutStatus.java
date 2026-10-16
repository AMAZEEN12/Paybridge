package com.academy.paybridge.remittance.client;

public enum PayoutStatus {
    PENDING,     // accepted, final outcome not known yet
    SUCCESS,     // the money reached the bank
    FAILED,      // definitely did not go (or was reversed)
    NOT_FOUND    // the partner has no record of this reference
}