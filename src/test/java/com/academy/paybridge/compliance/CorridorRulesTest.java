package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.CorridorResult;
import com.academy.paybridge.compliance.service.CorridorProperties.Rule;
import com.academy.paybridge.compliance.service.CorridorRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests (step B1, R-COR-1 to 3): first match wins, no match is refused, Nigeria-outbound can be switched off. */
class CorridorRulesTest {

    private static final List<Rule> SHIPPED = List.of(
            new Rule("NG", "*", true),
            new Rule("*", "NG", true),
            new Rule("*", "*", true));

    private static final List<Rule> NIGERIA_OUTBOUND_OFF = List.of(
            new Rule("NG", "*", false),
            new Rule("*", "NG", true),
            new Rule("*", "*", true));

    private static CorridorResult check(List<Rule> rules, String from, String to) {
        return CorridorRules.evaluate(rules, from, to);
    }

    @Test
    void theShippedTableAllowsNigeriaOutboundAndEveryOtherPair() {
        assertThat(check(SHIPPED, "NG", "GB").allowed()).isTrue();
        assertThat(check(SHIPPED, "GB", "NG").allowed()).isTrue();
        assertThat(check(SHIPPED, "GB", "US").allowed()).isTrue();
    }

    @Test
    void theFirstMatchingRuleWins() {
        List<Rule> rules = List.of(
                new Rule("NG", "GB", false),        // specific rule first: refuses NG to GB
                new Rule("NG", "*", true),
                new Rule("*", "*", true));

        CorridorResult refused = check(rules, "NG", "GB");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).contains("NG to GB");
        assertThat(check(rules, "NG", "US").allowed()).isTrue();      // falls to the next matching rule
    }

    @Test
    void aWildcardRuleBeforeASpecificOneHidesIt() {
        List<Rule> rules = List.of(new Rule("*", "*", true), new Rule("NG", "GB", false));

        assertThat(check(rules, "NG", "GB").allowed()).isTrue();      // order is the whole point of "first match"
    }

    @Test
    void aCorridorWithNoRuleIsRefused() {
        List<Rule> rules = List.of(new Rule("NG", "GB", true));

        CorridorResult r = check(rules, "NG", "US");

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("No corridor rule for NG to US");
    }

    @Test
    void anEmptyTableRefusesEveryCrossBorderCorridor() {
        assertThat(check(List.of(), "NG", "GB").allowed()).isFalse();
        assertThat(check(List.of(), "GB", "NG").allowed()).isFalse();
    }

    @Test
    void switchingNigeriaOutboundOffRefusesNigeriaToElsewhereOnly() {      // R-COR-3
        assertThat(check(NIGERIA_OUTBOUND_OFF, "NG", "GB").allowed()).isFalse();
        assertThat(check(NIGERIA_OUTBOUND_OFF, "NG", "US").allowed()).isFalse();
        assertThat(check(NIGERIA_OUTBOUND_OFF, "GB", "NG").allowed()).isTrue();    // remittance to Nigeria is unaffected
        assertThat(check(NIGERIA_OUTBOUND_OFF, "GB", "US").allowed()).isTrue();
    }

    @Test
    void aPayoutInsideNigeriaIsNotACorridorSoTheSwitchDoesNotTouchIt() {
        assertThat(check(NIGERIA_OUTBOUND_OFF, "NG", "NG").allowed()).isTrue();
        assertThat(check(List.of(), "GB", "GB").allowed()).isTrue();
    }

    @Test
    void countriesAreMatchedIgnoringCaseAndSpaces() {
        assertThat(check(NIGERIA_OUTBOUND_OFF, " ng ", "gb").allowed()).isFalse();
        assertThat(check(List.of(new Rule(" gb ", "us", true)), "GB", "US").allowed()).isTrue();
    }

    @Test
    void anUnknownOrMalformedCountryIsRefused() {
        assertThat(check(SHIPPED, null, "GB").allowed()).isFalse();
        assertThat(check(SHIPPED, "NG", "").allowed()).isFalse();
        assertThat(check(SHIPPED, "NGA", "GB").allowed()).isFalse();
        assertThat(check(SHIPPED, "N1", "GB").allowed()).isFalse();
    }

    @Test
    void aRuleWithNoEnabledValueIsOff() {
        assertThat(new Rule("NG", "GB", false).enabled()).isFalse();
        assertThat(check(List.of(new Rule("NG", "GB", false)), "NG", "GB").allowed()).isFalse();
    }
}