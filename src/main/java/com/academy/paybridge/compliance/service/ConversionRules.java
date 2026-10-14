package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ConversionContext;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure evaluation of conversion limits and velocity: facts in, decision out.
 * No database, no Spring, making it straightforward to test (R-LIM-1 to R-LIM-4).
 */
public final class ConversionRules {

    private ConversionRules() {
    }

    public static FraudResult evaluate(ConversionLimitProperties p, ConversionContext c) {
        List<String> blocks = new ArrayList<>();

        long maxSingleMinor = p.conversionMaxUsd() * 100L;
        long maxDailyMinor = p.dailyMaxUsd() * 100L;

        if (c.amountUsdMinor() > maxSingleMinor) {
            blocks.add("amount exceeds single conversion limit of $" + p.conversionMaxUsd());
        }
        if (c.dailyOutgoingUsdMinor() + c.amountUsdMinor() > maxDailyMinor) {
            blocks.add("this conversion would exceed the daily conversion limit of $" + p.dailyMaxUsd());
        }
        if (c.recentConversionCount() >= p.velocityMaxConversions()) {
            blocks.add(c.recentConversionCount() + " conversions in the last " + p.velocityWindowMinutes()
                    + " minutes (limit " + p.velocityMaxConversions() + ")");
        }

        if (!blocks.isEmpty()) {
            return new FraudResult(Decision.BLOCK, blocks);
        }
        return FraudResult.allow();
    }
}
