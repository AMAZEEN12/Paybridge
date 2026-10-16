package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.api.DayItem;

import java.time.Instant;
import java.util.List;

/** Read-only door for settlement: payouts created in [from, to), in the payout currency. */
public interface PayoutDayApi {
    List<DayItem> payoutsBetween(Instant from, Instant to);
}