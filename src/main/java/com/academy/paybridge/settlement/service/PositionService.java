package com.academy.paybridge.settlement.service;

import com.academy.paybridge.ledger.api.LedgerPositionsApi;
import com.academy.paybridge.settlement.api.PositionReport;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final LedgerPositionsApi ledger;
    private final SettlementProperties props;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public PositionService(LedgerPositionsApi ledger, SettlementProperties props, CurrencyRegistry currencies,
                           Clock clock) {
        this.ledger = ledger;
        this.props = props;
        this.currencies = currencies;
        this.clock = clock;
    }

    /** R-SET-2: a position below its minimum is logged and flagged. It never blocks anybody. */
    public PositionReport report() {
        PositionReport report = PositionRules.build(clock.instant(), ledger.positions(), props.thresholds(),
                currencies::format);
        report.warnings().forEach(w -> log.warn("Settlement position below its minimum: {}", w));
        return report;
    }
}