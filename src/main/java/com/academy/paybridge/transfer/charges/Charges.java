package com.academy.paybridge.transfer.charges;

public record Charges(long feeKobo, long vatKobo, long stampDutyKobo) {

    public long totalKobo() {
        return feeKobo + vatKobo + stampDutyKobo;
    }
}
