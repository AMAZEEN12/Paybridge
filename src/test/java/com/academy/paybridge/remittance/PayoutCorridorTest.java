package com.academy.paybridge.remittance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance 14: with the Nigeria-outbound rule off, Nigerian senders cannot pay abroad; everything else is unaffected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "paybridge.corridors.rules[0].from=NG", "paybridge.corridors.rules[0].to=*", "paybridge.corridors.rules[0].enabled=false",
        "paybridge.corridors.rules[1].from=*", "paybridge.corridors.rules[1].to=NG", "paybridge.corridors.rules[1].enabled=true",
        "paybridge.corridors.rules[2].from=*", "paybridge.corridors.rules[2].to=*", "paybridge.corridors.rules[2].enabled=true"})
@ActiveProfiles("test")
class PayoutCorridorTest extends PayoutHttpSupport {

    @Test
    void aNigerianSenderPayingAbroadIsRefusedWithCorridorNotAllowed() {
        User ng = newUser("NG");
        fund(ng, "USD", "500");

        Resp r = quote(ng, "USD", "100", "GB", "200000", "12345678", "GIFT");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("CORRIDOR_NOT_ALLOWED");
        assertThat(balance(ng, "USD")).isEqualByComparingTo("500");
    }

    @Test
    void remittanceToNigeriaFromAnotherCountryIsNotAffected() {
        User gb = newUser("GB");
        fund(gb, "USD", "500");

        assertThat(quote(gb, "USD", "100", "NG", "058", "2000000091", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void otherPairsAreNotAffected() {
        User gb = newUser("GB");
        fund(gb, "USD", "500");

        assertThat(quote(gb, "USD", "100", "US", "021000021", "2000000092", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void aPayoutInsideNigeriaIsNotACorridor() {
        User ng = newUser("NG");
        fund(ng, "USD", "500");

        assertThat(quote(ng, "USD", "100", "NG", "058", "2000000093", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void switchingTheRuleOffStopsAQuoteAlreadyIssued() {
        // The quote is made while the rule is on in the default config (see PayoutFlowTest); here the rule is off at
        // confirm time too, so a Nigerian sender cannot reach confirm with any quote to a foreign bank (R-COR-4).
        User ng = newUser("NG");
        fund(ng, "USD", "500");

        assertThat(quote(ng, "USD", "100", "GB", "200000", "12345678", "GIFT").code()).isEqualTo("CORRIDOR_NOT_ALLOWED");
    }
}