package com.academy.paybridge.fx.scheduler;

import com.academy.paybridge.fx.service.RateService;
import com.academy.paybridge.shared.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes up every 10 minutes and asks the service whether a refresh is due. The service only calls the provider when
 * the last successful fetch is older than the refresh interval, so a restart or a sleeping host never costs extra calls.
 */
@Component
public class RateRefreshJob {

    private final RateService rates;
    private final AppProperties props;

    public RateRefreshJob(RateService rates, AppProperties props) {
        this.rates = rates;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${paybridge.fx.check-interval:PT10M}", initialDelayString = "PT1M")
    public void run() {
        if (props.scheduler() != null && !props.scheduler().enabled()) {
            return;
        }
        rates.refreshIfDue();
    }
}