package com.academy.paybridge.remittance.scheduler;

import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.remittance.service.PayoutSettlementService;
import com.academy.paybridge.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;

/**
 * Finds payouts still PENDING and asks the partner for their real state (R-PAY-8). It settles timeouts, missed
 * webhooks, and payouts that were debited but never sent because the app stopped between the two steps.
 * Settlement is safe even if two instances run it: the payout row is locked and settled once.
 */
@Component
public class PendingPayoutJob {

    private static final Logger log = LoggerFactory.getLogger(PendingPayoutJob.class);

    private final RemittanceRepository remittances;
    private final PayoutSettlementService settlement;
    private final AppProperties props;
    private final Clock clock;

    public PendingPayoutJob(RemittanceRepository remittances, PayoutSettlementService settlement,
                            AppProperties props, Clock clock) {
        this.remittances = remittances;
        this.settlement = settlement;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT12S")
    public void verifyPending() {
        if (props.scheduler() != null && !props.scheduler().enabled()) {
            return;
        }
        List<Remittance> stale = remittances.findStale(
                EnumSet.of(RemittanceStatus.PENDING, RemittanceStatus.RETRYING),
                clock.instant().minusSeconds(10), PageRequest.of(0, 50));
        for (Remittance r : stale) {
            try {
                settlement.settleFromGateway(r.getReference());
            } catch (RuntimeException e) {
                log.warn("Payout verify job failed for {}: {}", r.getReference(), e.getClass().getSimpleName());
            }
        }
    }
}