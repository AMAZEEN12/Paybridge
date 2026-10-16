package com.academy.paybridge.remittance.domain;

public enum RemittanceStatus {
    PENDING,      // reserved and sent, outcome not known yet (also after a timeout)
    RETRYING,     // retries on only: converted but not paid, trying again
    SUCCESSFUL,
    FAILED
}