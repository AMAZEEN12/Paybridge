package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudContext;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.shared.money.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules themselves: facts in, decision out. No database, no Spring, so each rule is easy to test.
 * Any BLOCK wins, otherwise any FLAG, otherwise ALLOW.
 */
public final class FraudRules {

    private FraudRules() {
    }

    public static FraudResult evaluate(FraudProperties p, FraudContext c, boolean destinationBlocked) {
        List<String> blocks = new ArrayList<>();
        List<String> flags = new ArrayList<>();

        if (destinationBlocked) {
            blocks.add("destination is on the blocked list");
        }
        if (c.amountKobo() > p.maxSingleTransferKobo()) {
            blocks.add("amount " + Money.format(c.amountKobo()) + " is over the single-transfer limit of "
                    + Money.format(p.maxSingleTransferKobo()));
        }
        if (c.outgoingTodayKobo() + c.amountKobo() > p.maxDailyOutgoingKobo()) {
            blocks.add("this would take today's outgoing total over " + Money.format(p.maxDailyOutgoingKobo()));
        }
        if (c.recentTransferCount() >= p.velocityMaxTransfers()) {
            blocks.add(c.recentTransferCount() + " transfers in the last " + p.velocityWindowMinutes()
                    + " minutes (limit " + p.velocityMaxTransfers() + ")");
        }
        if (c.firstTimeBeneficiary() && c.amountKobo() > p.newBeneficiaryFlagKobo()) {
            flags.add("large first payment to a new recipient");
        }
        // between 80% and 100% of what just arrived: money is passing straight through the account
        if (c.recentIncomingKobo() > 0 && c.amountKobo() <= c.recentIncomingKobo()
                && c.amountKobo() >= p.passThroughRatio() * c.recentIncomingKobo()) {
            flags.add("sending out most of the money that just arrived (possible pass-through)");
        }

        if (!blocks.isEmpty()) {
            List<String> all = new ArrayList<>(blocks);
            all.addAll(flags);
            return new FraudResult(Decision.BLOCK, all);
        }
        if (!flags.isEmpty()) {
            return new FraudResult(Decision.FLAG, flags);
        }
        return FraudResult.allow();
    }
}
