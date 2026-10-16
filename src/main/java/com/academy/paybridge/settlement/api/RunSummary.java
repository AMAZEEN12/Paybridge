package com.academy.paybridge.settlement.api;

import java.time.LocalDate;

/** What one day's run did. Running a day again reports {@code batchesCreated = 0}. */
public record RunSummary(LocalDate date, int batchesCreated, int batchesAlreadyThere) {
}