package com.academy.paybridge.compliance.api;

public interface CorridorApi {

    /** Sender country, destination country, and the currency of the wallet the payout is paid from. */
    CorridorResult check(String senderCountry, String destinationCountry, String sourceCurrency);

    /** The country pair alone. A rule that names source currencies does not match it. */
    default CorridorResult check(String senderCountry, String destinationCountry) {
        return check(senderCountry, destinationCountry, null);
    }
}