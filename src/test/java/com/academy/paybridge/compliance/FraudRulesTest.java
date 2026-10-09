package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudContext;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.service.FraudProperties;
import com.academy.paybridge.compliance.service.FraudRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRulesTest {

    private final FraudProperties props = new FraudProperties(
            100_000_000L,      // N1,000,000 per transfer
            500_000_000L,      // N5,000,000 per day
            5, 10,             // 5 transfers per 10 minutes
            20_000_000L,       // N200,000 to a new beneficiary
            30, 0.8,           // pass-through window and ratio
            3, 30);

    private FraudContext ctx(long amount, long today, int recent, boolean firstTime, long incoming) {
        return new FraudContext(1L, "2000000001", "2000000002", amount, today, recent, firstTime, incoming);
    }

    @Test
    void ordinaryTransferIsAllowed() {
        assertThat(FraudRules.evaluate(props, ctx(500_000, 0, 0, false, 0), false).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void singleTransferLimitBlocksOnlyAboveTheLine() {
        assertThat(FraudRules.evaluate(props, ctx(100_000_000L, 0, 0, false, 0), false).decision()).isEqualTo(Decision.ALLOW);
        assertThat(FraudRules.evaluate(props, ctx(100_000_001L, 0, 0, false, 0), false).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void dailyLimitBlocksWhenThisTransferWouldCrossIt() {
        assertThat(FraudRules.evaluate(props, ctx(1_000_000, 499_000_000L, 0, false, 0), false).decision())
                .isEqualTo(Decision.ALLOW);
        assertThat(FraudRules.evaluate(props, ctx(1_000_001, 499_000_000L, 0, false, 0), false).decision())
                .isEqualTo(Decision.BLOCK);
    }

    @Test
    void velocityBlocksTheSixthTransfer() {
        assertThat(FraudRules.evaluate(props, ctx(1000, 0, 4, false, 0), false).decision()).isEqualTo(Decision.ALLOW);
        assertThat(FraudRules.evaluate(props, ctx(1000, 0, 5, false, 0), false).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void largeFirstPaymentToNewRecipientIsFlaggedNotBlocked() {
        FraudResult r = FraudRules.evaluate(props, ctx(20_000_001L, 0, 0, true, 0), false);
        assertThat(r.decision()).isEqualTo(Decision.FLAG);
        assertThat(FraudRules.evaluate(props, ctx(20_000_000L, 0, 0, true, 0), false).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void passThroughIsFlaggedWhenMostOfWhatArrivedLeavesAgain() {
        assertThat(FraudRules.evaluate(props, ctx(900_000, 0, 0, false, 1_000_000), false).decision()).isEqualTo(Decision.FLAG);
        assertThat(FraudRules.evaluate(props, ctx(100_000, 0, 0, false, 1_000_000), false).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void blockedDestinationAlwaysBlocksAndABlockBeatsAFlag() {
        assertThat(FraudRules.evaluate(props, ctx(1000, 0, 0, false, 0), true).decision()).isEqualTo(Decision.BLOCK);
        FraudResult both = FraudRules.evaluate(props, ctx(20_000_001L, 0, 5, true, 0), false);
        assertThat(both.decision()).isEqualTo(Decision.BLOCK);
        assertThat(both.reasons()).hasSizeGreaterThan(1);
    }
}
