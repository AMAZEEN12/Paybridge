package com.academy.paybridge.transfer.scheduler;

import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.repository.TransferRepository;
import com.academy.paybridge.transfer.service.TransferSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The safety net behind the webhook. Every 15 seconds it asks the provider about payouts that are still
 * PENDING. It settles timeouts ("did it go or not?"), missed webhooks, and payouts that were debited
 * but never sent because the app crashed in between.
 *
 * Note: with more than one app instance you would need a lock (or a queue) so two instances do not
 * handle the same transfer. Settlement is safe anyway because the transfer row is locked, but it is wasteful.
 */
@Component
public class PendingTransferJob {

    private static final Logger log = LoggerFactory.getLogger(PendingTransferJob.class);

    private final TransferRepository transfers;
    private final TransferSettlementService settlement;
    private final AppProperties props;
    private final Clock clock;

    public PendingTransferJob(TransferRepository transfers, TransferSettlementService settlement,
                              AppProperties props, Clock clock) {
        this.transfers = transfers;
        this.settlement = settlement;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT10S")
    public void verifyPending() {
        if (props.scheduler() != null && !props.scheduler().enabled()) {
            return;
        }
        List<Transfer> pending = transfers.findTop50ByStatusAndTypeAndCreatedAtBeforeOrderByCreatedAtAsc(
                TransferStatus.PENDING, TransferType.EXTERNAL, clock.instant().minus(10, ChronoUnit.SECONDS));
        for (Transfer t : pending) {
            try {
                settlement.settleFromGateway(t.getReference());
            } catch (RuntimeException e) {
                log.warn("Verify job failed for {}: {}", t.getReference(), e.getClass().getSimpleName());
            }
        }
    }
}
