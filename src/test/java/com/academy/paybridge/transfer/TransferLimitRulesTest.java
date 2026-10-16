package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitContext;
import com.academy.paybridge.compliance.api.TransferLimitContext.Kind;
import com.academy.paybridge.compliance.service.TransferLimitProperties;
import com.academy.paybridge.compliance.service.TransferLimitRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferLimitRulesTest {

    private final TransferLimitProperties props = new TransferLimitProperties(1000L, 1000L, 5000L);

    private FraudResult check(Kind kind, long amountCents, long usedCents) {
        return TransferLimitRules.evaluate(props, new TransferLimitContext(kind, amountCents, usedCents));
    }

    @Test
    void exactlyTheSingleLimitIsAllowedAndOneCentOverIsBlocked() {
        assertThat(check(Kind.TRANSFER, 100_000L, 0L).decision()).isEqualTo(Decision.ALLOW);

        FraudResult over = check(Kind.TRANSFER, 100_001L, 0L);
        assertThat(over.decision()).isEqualTo(Decision.BLOCK);
        assertThat(over.joinedReasons()).contains("single transfer limit of $1000");
    }

    @Test
    void aPayoutUsesItsOwnSingleLimit() {
        TransferLimitProperties tighter = new TransferLimitProperties(1000L, 500L, 5000L);

        FraudResult r = TransferLimitRules.evaluate(tighter,
                new TransferLimitContext(Kind.PAYOUT, 60_000L, 0L));

        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.joinedReasons()).contains("single payout limit of $500");
    }

    @Test
    void fiveThousandDollarsInADayIsAllowedAndOneMoreDollarIsBlocked() {   // acceptance 21d
        assertThat(check(Kind.TRANSFER, 100_000L, 400_000L).decision()).isEqualTo(Decision.ALLOW);

        FraudResult r = check(Kind.TRANSFER, 100L, 500_000L);
        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.joinedReasons()).contains("daily limit of $5000");
    }

    @Test
    void bothReasonsAreReportedWhenBothLimitsAreBroken() {
        FraudResult r = check(Kind.TRANSFER, 200_000L, 400_000L);

        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.reasons()).hasSize(2);
    }

    @Test
    void defaultsApplyWhenNothingIsConfigured() {
        TransferLimitProperties p = new TransferLimitProperties(0L, 0L, 0L);

        assertThat(p.transferMaxUsd()).isEqualTo(1000L);
        assertThat(p.payoutMaxUsd()).isEqualTo(1000L);
        assertThat(p.dailyMaxUsd()).isEqualTo(5000L);
    }
}