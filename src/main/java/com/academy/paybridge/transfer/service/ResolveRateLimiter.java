package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name enquiry tells a caller who owns a bank account, so it must not be usable as a lookup tool.
 * At most N lookups per customer per minute. In memory: enough for one app instance.
 */
@Component
public class ResolveRateLimiter {

    private final ConcurrentHashMap<Long, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;

    public ResolveRateLimiter(AppProperties props, Clock clock) {
        this.limitPerMinute = props.resolveLimitPerMinute() > 0 ? props.resolveLimitPerMinute() : 10;
        this.clock = clock;
    }

    public void check(Long customerId) {
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(60);
        Deque<Instant> window = hits.computeIfAbsent(customerId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                throw ApiException.tooManyRequests("TOO_MANY_LOOKUPS",
                        "Too many account lookups. Please wait a minute.");
            }
            window.addLast(now);
        }
    }
}
