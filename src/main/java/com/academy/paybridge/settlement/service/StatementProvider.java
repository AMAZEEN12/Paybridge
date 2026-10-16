package com.academy.paybridge.settlement.service;

import java.time.Instant;
import java.util.List;

/**
 * A partner's statement for a UTC date range, returned page by page (R-SET-6). The real Paystack and Flutterwave
 * versions come when their list shapes are confirmed (O8). Nothing in tests or local runs calls a real partner.
 */
public interface StatementProvider {

    /** The partner this provider reads, for example FLUTTERWAVE. */
    String partner();

    /** Zero-based page of the partner's payouts in {@code currency} created in [from, to). */
    Page list(String currency, Instant from, Instant to, int page);

    record Line(String reference, String currency, long amountMinor, String status, Instant partnerTime) {
    }

    record Page(List<Line> lines, boolean hasMore) {
    }
}