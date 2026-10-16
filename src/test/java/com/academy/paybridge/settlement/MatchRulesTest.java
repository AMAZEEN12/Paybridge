package com.academy.paybridge.settlement;

import com.academy.paybridge.settlement.service.MatchRules;
import com.academy.paybridge.settlement.service.MatchRules.Ours;
import com.academy.paybridge.settlement.service.MatchRules.Result;
import com.academy.paybridge.settlement.service.MatchRules.Theirs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 002-004, step C3 (R-SET-8, R-SET-9): the exception type from a statement line. */
class MatchRulesTest {

    private static Ours ours(String status) { return new Ours(10_000, status); }
    private static Theirs theirs(long amount, String status) { return new Theirs(amount, status); }

    @Test
    void sameAmountAndStatusMatches() {
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), theirs(10_000, "SUCCESS"), true)).isEqualTo(Result.MATCHED);
        assertThat(MatchRules.decide(ours("FAILED"), theirs(10_000, "FAILED"), true)).isEqualTo(Result.MATCHED);
    }

    @Test
    void differentAmountIsAmountDiffers() {
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), theirs(9_900, "SUCCESS"), true)).isEqualTo(Result.AMOUNT_DIFFERS);
    }

    @Test
    void differentStatusIsStatusDiffers() {
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), theirs(10_000, "FAILED"), true)).isEqualTo(Result.STATUS_DIFFERS);
        assertThat(MatchRules.decide(ours("FAILED"), theirs(10_000, "SUCCESS"), true)).isEqualTo(Result.STATUS_DIFFERS);
    }

    @Test
    void pendingOrRetryingIsStillPendingWhateverThePartnerSays() {
        assertThat(MatchRules.decide(ours("PENDING"), null, false)).isEqualTo(Result.STILL_PENDING);
        assertThat(MatchRules.decide(ours("PENDING"), theirs(10_000, "SUCCESS"), true)).isEqualTo(Result.STILL_PENDING);
        assertThat(MatchRules.decide(ours("RETRYING"), null, true)).isEqualTo(Result.STILL_PENDING);
    }

    @Test
    void missingAtPartnerOnlyAfterTheLag() {
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), null, false)).isEqualTo(Result.AWAITING_PARTNER);
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), null, true)).isEqualTo(Result.MISSING_AT_PARTNER);
    }

    @Test
    void aFailedPayoutThePartnerNeverSawIsFine() {
        assertThat(MatchRules.decide(ours("FAILED"), null, true)).isEqualTo(Result.MATCHED);
    }

    @Test
    void aPartnerThatIsStillPendingIsLateNotWrongUntilTheLagPasses() {
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), theirs(10_000, "PENDING"), false)).isEqualTo(Result.AWAITING_PARTNER);
        assertThat(MatchRules.decide(ours("SUCCESSFUL"), theirs(10_000, "PENDING"), true)).isEqualTo(Result.STATUS_DIFFERS);
    }

    @Test
    void onlyTheFiveTypesAreExceptions() {
        assertThat(Result.MATCHED.isException()).isFalse();
        assertThat(Result.AWAITING_PARTNER.isException()).isFalse();
        assertThat(java.util.Arrays.stream(Result.values()).filter(Result::isException)).hasSize(5);
    }
}