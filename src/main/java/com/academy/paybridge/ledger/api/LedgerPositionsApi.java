package com.academy.paybridge.ledger.api;

import java.util.List;

/** Read-only door for the settlement module: what every ledger account holds, worked out from the lines (R-SET-1). */
public interface LedgerPositionsApi {

    /** Every ledger key and currency with its credits minus debits. Never stored, always derived. */
    List<AccountPosition> positions();
}