package com.academy.paybridge.settlement.api;

import java.time.Instant;
import java.time.LocalDate;

/** A batch as operators see it. */
public record BatchView(Long id, String partner, String currency, LocalDate periodDate, Instant periodStart,
                        Instant periodEnd, String status, int itemCount, long ourTotalMinor, long partnerTotalMinor,
                        Instant closedAt) {
}