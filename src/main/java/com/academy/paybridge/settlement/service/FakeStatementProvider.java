package com.academy.paybridge.settlement.service;

import com.academy.paybridge.remittance.api.PayoutDayApi;
import com.academy.paybridge.shared.api.DayItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The partner statement for tests and local runs. It mirrors our own payouts (so a normal day matches), and a test can
 * hide a payout, change its amount or status, or add a line we do not have, to build the exceptions of step C3.
 */
@Component
@ConditionalOnProperty(name = "paybridge.settlement.statement-provider", havingValue = "fake", matchIfMissing = true)
public class FakeStatementProvider implements StatementProvider {

    static final int PAGE_SIZE = 100;

    private final PayoutDayApi payouts;
    private final Set<String> hidden = ConcurrentHashMap.newKeySet();
    private final Map<String, Line> overrides = new ConcurrentHashMap<>();
    private final List<Line> extra = new CopyOnWriteArrayList<>();

    public FakeStatementProvider(PayoutDayApi payouts) {
        this.payouts = payouts;
    }

    @Override
    public String partner() {
        return "FLUTTERWAVE";
    }

    @Override
    public Page list(String currency, Instant from, Instant to, int page) {
        List<Line> all = new java.util.ArrayList<>();
        for (DayItem item : payouts.payoutsBetween(from, to)) {
            if (!item.currency().equals(currency) || hidden.contains(item.reference())) {
                continue;
            }
            all.add(overrides.getOrDefault(item.reference(),
                    new Line(item.reference(), item.currency(), item.amountMinor(), partnerStatus(item.status()), from)));
        }
        extra.stream().filter(l -> l.currency().equals(currency)
                && !l.partnerTime().isBefore(from) && l.partnerTime().isBefore(to)).forEach(all::add);
        int start = Math.min(page * PAGE_SIZE, all.size());
        int end = Math.min(start + PAGE_SIZE, all.size());
        return new Page(List.copyOf(all.subList(start, end)), end < all.size());
    }

    /** Our status to the word a partner would use. */
    static String partnerStatus(String ours) {
        return switch (ours) {
            case "SUCCESSFUL" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            default -> "PENDING";
        };
    }

    public void hide(String reference) { hidden.add(reference); }

    public void override(Line line) { overrides.put(line.reference(), line); }

    public void addExtra(Line line) { extra.add(line); }

    public void reset() {
        hidden.clear();
        overrides.clear();
        extra.clear();
    }
}