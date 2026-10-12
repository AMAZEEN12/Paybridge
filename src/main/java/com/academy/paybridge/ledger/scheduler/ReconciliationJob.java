package com.academy.paybridge.ledger.scheduler;

import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the reconciliation on a timer (default every hour) and leaves a line in the audit log either way:
 * RECONCILIATION_OK or RECONCILIATION_MISMATCH. A mismatch is also logged as an ERROR so it is easy to alert on.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;
    private final AuditService audit;
    private final AppProperties props;
    private final boolean enabled;

    public ReconciliationJob(ReconciliationService reconciliation, AuditService audit, AppProperties props,
                             @Value("${paybridge.reconciliation.enabled:true}") boolean enabled) {
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.props = props;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${paybridge.reconciliation.interval:PT1H}", initialDelayString = "PT2M")
    public void run() {
        if (!enabled || (props.scheduler() != null && !props.scheduler().enabled())) {
            return;
        }
        try {
            ReconciliationReport report = reconciliation.run();
            if (report.ok()) {
                audit.recordIndependently("system", "RECONCILIATION_OK", null,
                        report.entries() + " ledger lines in " + report.journals() + " journals, all balanced");
            } else {
                log.error("RECONCILIATION MISMATCH: {} problem(s). First: {}", report.problems().size(),
                        report.problems().get(0));
                audit.recordIndependently("system", "RECONCILIATION_MISMATCH", null,
                        report.problems().size() + " problem(s). First: " + report.problems().get(0));
            }
        } catch (RuntimeException e) {
            log.warn("Reconciliation could not run: {}", e.getClass().getSimpleName());
        }
    }
}