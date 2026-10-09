package com.academy.paybridge.transfer.gateway;

public enum GatewayStatus {
    PENDING,     // accepted, final outcome not known yet
    SUCCESS,     // money reached the bank
    FAILED,      // definitely did not go (or was reversed)
    NOT_FOUND    // the provider has no record of this reference
}
