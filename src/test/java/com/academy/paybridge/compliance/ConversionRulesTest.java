package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.ConversionContext;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.service.ConversionLimitProperties;
import com.academy.paybridge.compliance.service.ConversionRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionRulesTest {

    private final ConversionLimitProperties props = new ConversionLimitProperties(
            1000L,  // $1,000 max single conversion (100,000 cents)
            5000L,  // $5,000 max daily conversions (500,000 cents)
            5,      // 5 conversions max
            10      // 10 minutes velocity window
    );

    @Test
    void ordinaryConversionIsAllowed() {
        FraudResult r = ConversionRules.evaluate(props, new ConversionContext(1L, 50_000L, 0L, 0));
        assertThat(r.decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void singleConversionLimitEnforced() {
        // Exactly $1,000 (100,000 cents) allowed
        assertThat(ConversionRules.evaluate(props, new ConversionContext(1L, 100_000L, 0L, 0)).decision())
                .isEqualTo(Decision.ALLOW);

        // $1,000.01 (100,001 cents) blocked
        FraudResult r = ConversionRules.evaluate(props, new ConversionContext(1L, 100_001L, 0L, 0));
        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.joinedReasons()).contains("exceeds single conversion limit");
    }

    @Test
    void dailyConversionLimitEnforced() {
        // $4,500 previously + $500 this conversion = $5,000 (500,000 cents) allowed
        assertThat(ConversionRules.evaluate(props, new ConversionContext(1L, 50_000L, 450_000L, 0)).decision())
                .isEqualTo(Decision.ALLOW);

        // $4,500 previously + $500.01 this conversion = $5,000.01 blocked
        FraudResult r = ConversionRules.evaluate(props, new ConversionContext(1L, 50_001L, 450_000L, 0));
        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.joinedReasons()).contains("daily conversion limit");
    }

    @Test
    void velocityBlocksFifthConversion() {
        // 4 conversions in the window is allowed
        assertThat(ConversionRules.evaluate(props, new ConversionContext(1L, 10_000L, 40_000L, 4)).decision())
                .isEqualTo(Decision.ALLOW);

        // 5th conversion in the window is blocked
        FraudResult r = ConversionRules.evaluate(props, new ConversionContext(1L, 10_000L, 40_000L, 5));
        assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        assertThat(r.joinedReasons()).contains("conversions in the last");
    }
}
