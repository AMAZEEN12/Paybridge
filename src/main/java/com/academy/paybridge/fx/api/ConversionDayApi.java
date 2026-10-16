package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.api.DayItem;

import java.time.Instant;
import java.util.List;

/** Read-only door for settlement: conversions completed in [from, to), in the source currency. */
public interface ConversionDayApi {
    List<DayItem> conversionsBetween(Instant from, Instant to);
}