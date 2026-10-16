package com.academy.paybridge.settlement.scheduler;

import com.academy.paybridge.settlement.service.SettlementBatchService;
import com.academy.paybridge.settlement.service.SettlementMatchingService;
import com.academy.paybridge.settlement.service.SettlementProperties;
import com.academy.paybridge.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Checks every minute whether the daily run time (paybridge.settlement.run-at, UTC) has passed, then builds the batches
 * for every UTC day not yet run (R-SET-4) and matches every batch that has a matching pass due (C3, R-SET-8).
 * Because it only needs "time has passed", a host that was asleep at 00:30 catches up when it wakes.
 * Off until paybridge.settlement.enabled is true.
 */
@Component
public class SettlementBatchJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementBatchJob.class);

    private final SettlementBatchService service;
    private final SettlementMatchingService matching;
    private final SettlementProperties props;
    private final AppProperties app;
    private final Clock clock;
    private final boolean enabled;

    public SettlementBatchJob(SettlementBatchService service, SettlementMatchingService matching,
                              SettlementProperties props, AppProperties app, Clock clock,
                              @Value("${paybridge.settlement.enabled:false}") boolean enabled) {
        this.service = service;
        this.matching = matching;
        this.props = props;
        this.app = app;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void run() {
        if (!enabled || (app.scheduler() != null && !app.scheduler().enabled())) {
            return;
        }
        if (LocalTime.now(clock.withZone(ZoneOffset.UTC)).isBefore(LocalTime.parse(props.runAt()))) {
            return;
        }
        try {
            service.catchUp();
        } catch (RuntimeException e) {
            log.warn("Settlement run could not finish: {}", e.getClass().getSimpleName());
        }
        try {
            matching.matchDue();
        } catch (RuntimeException e) {
            log.warn("Settlement matching could not finish: {}", e.getClass().getSimpleName());
        }
    }
}