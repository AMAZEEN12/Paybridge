package com.academy.paybridge.remittance.service;

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
 * Protects the partner's lookup allowance (R-VER-5), like the Paystack ResolveBudget:
 *  1. A name the partner really gave is remembered for a while, so the "check name" step and the payout do not
 *     spend two lookups. Only real names are remembered; a simulated one never is.
 *  2. A daily counter (0 = off) stops the app calling the partner once today's allowance is spent.
 * Both live in memory and reset on a restart. The day is counted in UTC. The cache key holds the account number but
 * nothing here ever logs it.
 */
@Component
public class VerificationBudget {

    private record Remembered(String name, Instant expires) {
    }

    private final Map<String, Remembered> names = new ConcurrentHashMap<>();
    private final int dailyLimit;
    private final Duration keepFor;
    private final Clock clock;

    private LocalDate day;
    private int used;

    public VerificationBudget(PayoutProperties props, Clock clock) {
        this.dailyLimit = props.flutterwave().verifyDailyLimit();
        this.keepFor = Duration.ofHours(props.flutterwave().verifyCacheHours());
        this.clock = clock;
    }

    public static String key(String country, String bankCode, String accountNumber) {
        return country + ":" + (bankCode == null ? "" : bankCode) + ":" + accountNumber;
    }

    public Optional<String> remembered(String key) {
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

    public void remember(String key, String name) {
        names.put(key, new Remembered(name, clock.instant().plus(keepFor)));
    }

    /** Takes one lookup from today's allowance. False means it is spent: do not call the partner. */
    public synchronized boolean trySpend() {
        if (dailyLimit == 0) {
            return true;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= dailyLimit) {
            return false;
        }
        used++;
        return true;
    }
}