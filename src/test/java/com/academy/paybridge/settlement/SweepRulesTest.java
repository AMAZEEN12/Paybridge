package com.academy.paybridge.settlement;

import com.academy.paybridge.ledger.api.AccountPosition;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.settlement.service.SweepRules;
import com.academy.paybridge.settlement.service.SweepRules.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 002-004, step C4 (R-SET-11): the pure sweep rules. */
class SweepRulesTest {

    private static final List<AccountPosition> ROWS = List.of(
            new AccountPosition("EXT:FX_FEE:USD", "USD", 300),
            new AccountPosition("EXT:PAYOUT_FEE:EUR", "EUR", 80),
            new AccountPosition("9000000001:NGN", "NGN", 7_000),
            new AccountPosition("EXT:FX_POOL:USD", "USD", 5_000));

    private static Plan plan(List<Plan> plans, String source) {
        return plans.stream().filter(p -> p.source().equals(source)).findFirst().orElseThrow();
    }

    @Test
    void naijaIsAlwaysThereAndOtherCurrenciesAppearOnceTheyHaveAFee() {
        List<Plan> plans = SweepRules.plans(ROWS, null);

        assertThat(plans).extracting(Plan::currency).contains("NGN", "USD", "EUR").doesNotContain("GBP");
        assertThat(SweepRules.plans(List.of(), null)).extracting(Plan::currency).containsOnly("NGN");
    }

    @Test
    void feesGoToRevenueAndVatAndStampDutyGoToTaxPayable() {
        List<Plan> plans = SweepRules.plans(ROWS, null);

        assertThat(plan(plans, "EXT:FX_FEE:USD").destination()).isEqualTo("EXT:REVENUE:USD");
        assertThat(plan(plans, "EXT:PAYOUT_FEE:EUR").destination()).isEqualTo("EXT:REVENUE:EUR");
        assertThat(plan(plans, "9000000001:NGN").destination()).isEqualTo("EXT:REVENUE:NGN");
        assertThat(plan(plans, "9000000002:NGN").destination()).isEqualTo("EXT:TAX_PAYABLE:NGN");
        assertThat(plan(plans, "9000000003:NGN").destination()).isEqualTo("EXT:TAX_PAYABLE:NGN");
    }

    @Test
    void onlyTheNairaSystemWalletsAreDebitedAsWallets() {
        List<Plan> plans = SweepRules.plans(ROWS, null);

        assertThat(plan(plans, "9000000001:NGN").walletAccount()).isEqualTo("9000000001");
        assertThat(plan(plans, "EXT:FX_FEE:USD").walletAccount()).isNull();
        assertThat(plan(plans, "EXT:FX_FEE:NGN").walletAccount()).isNull();
    }

    @Test
    void aCurrencyFilterKeepsOnlyThatCurrency() {
        assertThat(SweepRules.plans(ROWS, "USD")).extracting(Plan::currency).containsOnly("USD");
        assertThat(SweepRules.plans(ROWS, "XYZ")).isEmpty();
    }

    @Test
    void theAmountIsTheNetPositionAndNeverNegative() {
        List<Plan> plans = SweepRules.plans(ROWS, null);

        assertThat(SweepRules.amountOf(ROWS, plan(plans, "EXT:FX_FEE:USD"))).isEqualTo(300);
        assertThat(SweepRules.amountOf(ROWS, plan(plans, "9000000002:NGN"))).isZero();
        List<AccountPosition> negative = List.of(new AccountPosition("EXT:FX_FEE:USD", "USD", -5));
        assertThat(SweepRules.amountOf(negative, plan(plans, "EXT:FX_FEE:USD"))).isZero();
    }

    @Test
    void theJournalDebitsTheSourceAndCreditsTheDestinationByTheSameAmount() {
        Plan p = plan(SweepRules.plans(ROWS, null), "EXT:FX_FEE:USD");

        List<JournalLine> lines = SweepRules.lines(p, 300);

        assertThat(lines).containsExactly(
                new JournalLine("EXT:FX_FEE:USD", JournalLine.Side.DEBIT, "USD", 300),
                new JournalLine("EXT:REVENUE:USD", JournalLine.Side.CREDIT, "USD", 300));
    }
}