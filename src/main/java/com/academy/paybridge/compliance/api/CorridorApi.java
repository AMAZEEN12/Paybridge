package com.academy.paybridge.compliance.api;

/**
 * The corridor rule table (R-COR-1 to 4). The remittance module checks it at quote time and again at confirm time
 * and, when it says no, refuses with CORRIDOR_NOT_ALLOWED.
 */
public interface CorridorApi {

    /** Two-letter country codes: the sender's stored country and the destination bank's country (R-COR-2). */
    CorridorResult check(String senderCountry, String destinationCountry);
}