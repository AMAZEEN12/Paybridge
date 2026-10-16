package com.academy.paybridge.settlement.api;

import java.time.Instant;
import java.util.List;

/** What one sweep request did: swept, skipped because the balance was zero, or failed. */
public record SweepReport(Instant at, List<SweepView> swept, List<String> nothingToSweep, List<Failure> failed) {

    public record Failure(String source, String reason) {
    }
}