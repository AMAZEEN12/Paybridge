package com.academy.paybridge.fx.scheduler;

import com.academy.paybridge.fx.service.QuoteService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically marks quotes stuck in EXECUTING longer than the limit as FAILED (R-QUO-8).
 */
@Component
@ConditionalOnProperty(name = "paybridge.scheduler.enabled", matchIfMissing = true)
public class QuoteCleanupJob {

    private final QuoteService quotes;

    public QuoteCleanupJob(QuoteService quotes) {
        this.quotes = quotes;
    }

    @Scheduled(fixedRateString = "${paybridge.quotes.cleanup-interval:PT1M}")
    public void cleanup() {
        quotes.cleanupQuotes();
    }
}
