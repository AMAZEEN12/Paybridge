package com.academy.paybridge.settlement.api;

import java.time.Instant;
import java.time.LocalDate;

/** An exception as operators see it. Resolution fields are null while it is open. */
public record ExceptionView(Long id, Long batchId, String partner, String currency, LocalDate periodDate,
                            String reference, String type, Long ourAmountMinor, Long partnerAmountMinor,
                            String ourStatus, String partnerStatus, Instant createdAt, Instant resolvedAt,
                            String resolvedBy, String resolutionNote) {
}