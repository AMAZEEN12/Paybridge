package com.academy.paybridge.remittance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PayoutSourceCurrencyCorridorTest extends PayoutHttpSupport {

    @Test
    void aNigerianSenderCannotPayAbroadStraightFromTheNairaWallet() {
        User ng = newUser("NG");
        fund(ng, "NGN", "5000000");

        Resp r = quote(ng, "NGN", "200000", "GB", "200000", "12345678", "GIFT");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("CORRIDOR_NOT_ALLOWED");
        assertThat(balance(ng, "NGN")).isEqualByComparingTo("5000000");
    }

    @Test
    void aNigerianSenderCanPayAbroadFromAUsdWallet() {
        User ng = newUser("NG");
        fund(ng, "USD", "500");

        assertThat(quote(ng, "USD", "100", "GB", "200000", "12345678", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void aNigerianSenderCanPayAbroadFromAGbpWallet() {
        User ng = newUser("NG");
        fund(ng, "GBP", "500");

        assertThat(quote(ng, "GBP", "100", "GB", "200000", "12345678", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void aSenderInAnotherCountryIsNotAffected() {
        User gb = newUser("GB");
        fund(gb, "GBP", "500");

        assertThat(quote(gb, "GBP", "100", "NG", "058", "2000000094", "GIFT").status()).isEqualTo(200);
    }
}