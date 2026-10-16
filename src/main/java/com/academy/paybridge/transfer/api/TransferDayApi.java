package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.api.DayItem;

import java.time.Instant;
import java.util.List;

/** Read-only door for settlement: PayBridge-to-PayBridge transfers created in [from, to). */
public interface TransferDayApi {
    List<DayItem> internalTransfersBetween(Instant from, Instant to);
}