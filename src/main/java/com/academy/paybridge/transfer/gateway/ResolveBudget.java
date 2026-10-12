package com.academy.paybridge.transfer.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects the few live name lookups Paystack gives a TEST account (about 3 a day).
 *
 *  1. A name already found is remembered for a while, so asking about the same account again costs nothing.
 *     This matters because both the "check name" button and the transfer itself ask for the name.
 *  2. A counter stops the app from calling Paystack once today's allowance is spent, and says so clearly
 *     instead of failing in a confusing way.
 *
 * Both live in memory, so they reset when the app restarts (Render's free tier sleeps and restarts). If that
 * happens, Paystack still has its own count: its refusal is handled as well. The day is counted in UTC.
 * Set PAYSTACK_RESOLVE_DAILY_LIMIT=0 to switch the counter off (for a live key).
 */
@Component
public class ResolveBudget {

    private record Remembered(String name, Instant expires) {
    }

    private final Map<String, Remembered> names = new ConcurrentHashMap<>();
    private final int dailyLimit;
    private final Duration keepFor;
    private final Clock clock;

    private LocalDate day;
    private int used;

    public ResolveBudget(@Value("${paybridge.paystack.resolve-daily-limit:3}") int dailyLimit,
                         @Value("${paybridge.paystack.resolve-cache-hours:24}") int cacheHours,
                         Clock clock) {
        this.dailyLimit = Math.max(0, dailyLimit);
        this.keepFor = Duration.ofHours(Math.max(1, cacheHours));
        this.clock = clock;
    }

    public Optional<String> remembered(String bankCode, String accountNumber) {
        String key = bankCode + ":" + accountNumber;
        Remembered r = names.get(key);
        if (r == null) {
            return Optional.empty();
        }
        if (r.expires().isBefore(clock.instant())) {
            names.remove(key);
            return Optional.empty();
        }
        return Optional.of(r.name());
    }

    public void remember(String bankCode, String accountNumber, String name) {
        names.put(bankCode + ":" + accountNumber, new Remembered(name, clock.instant().plus(keepFor)));
    }

    /** Takes one lookup from today's allowance, or throws if there is none left. */
    public synchronized void spend() {
        if (dailyLimit == 0) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= dailyLimit) {
            throw new LookupLimitReachedException(dailyLimit);
        }
        used++;
    }
}