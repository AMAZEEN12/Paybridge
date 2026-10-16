package com.academy.paybridge.transfer.service;

import java.time.Instant;
import java.time.ZoneId;

/** The day the daily limit is counted over: midnight to midnight in the app's zone, Lagos by default (S10, S12). */
public final class LagosDay {

    private LagosDay() {
    }

    /** The first instant of the zone's calendar day that contains {@code now}. */
    public static Instant startOf(Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }
}