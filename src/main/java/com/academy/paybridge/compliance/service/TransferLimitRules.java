package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitContext;

import java.util.ArrayList;
import java.util.List;

/** Pure rules: facts in, decision out. No database, no Spring (R-LIM2-2). */
public final class TransferLimitRules {

    private TransferLimitRules() {
    }

    public static FraudResult evaluate(TransferLimitProperties p, TransferLimitContext c) {
        List<String> blocks = new ArrayList<>();

        boolean payout = c.kind() == TransferLimitContext.Kind.PAYOUT;
        long maxSingleUsd = payout ? p.payoutMaxUsd() : p.transferMaxUsd();

        if (c.amountUsdMinor() > maxSingleUsd * 100L) {
            blocks.add("amount exceeds the single " + (payout ? "payout" : "transfer")
                    + " limit of $" + maxSingleUsd);
        }
        if (c.dailyUsdMinor() + c.amountUsdMinor() > p.dailyMaxUsd() * 100L) {
            blocks.add("this would exceed the daily limit of $" + p.dailyMaxUsd()
                    + " for transfers and payouts");
        }

        return blocks.isEmpty() ? FraudResult.allow() : new FraudResult(Decision.BLOCK, blocks);
    }
}