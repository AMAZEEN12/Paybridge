package com.academy.paybridge.remittance;

import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step B2 over real HTTP: destinations and account verification with the fake partner, in the default auto mode.
 * Nigeria is verifiable, so it gets a real (fake-partner) name; the United Kingdom is not, so it gets the labelled
 * simulated fallback. Acceptance 21b and 21c.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PayoutVerificationEndpointsTest {

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    record Resp(int status, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }

        String code() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
        }
    }

    Resp call(HttpMethod method, String path, String token, Object body) {
        RestClient client = RestClient.create("http://localhost:" + port);
        RestClient.RequestBodySpec spec = client.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            Object parsed = null;
            try {
                parsed = response.bodyTo(Object.class);
            } catch (RuntimeException ignored) {
                // empty body
            }
            return new Resp(response.getStatusCode().value(), parsed);
        });
    }

    String token() {
        String email = "pv-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test Payer", "email", email, "password", "password123")).status()).isEqualTo(201);
        return (String) call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123")).map().get("token");
    }

    Resp verify(String token, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/v1/payouts/verify-account", token, body);
    }

    @Test
    void destinationsListCountriesCurrenciesFieldsAndPurposes() {
        Resp r = call(HttpMethod.GET, "/api/v1/payouts/destinations", token(), null);

        assertThat(r.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> countries = (List<Map<String, Object>>) r.map().get("countries");
        assertThat(countries).extracting(c -> c.get("code")).contains("NG", "GB", "US");
        Map<String, Object> ng = countries.stream().filter(c -> c.get("code").equals("NG")).findFirst().orElseThrow();
        assertThat(ng).containsEntry("currency", "NGN");
        assertThat((List<?>) ng.get("bankFields")).isNotEmpty();
        assertThat((List<String>) r.map().get("purposes"))
                .contains("FAMILY_SUPPORT", "EDUCATION", "OTHER");
    }

    @Test
    void bothEndpointsNeedALogin() {
        assertThat(call(HttpMethod.GET, "/api/v1/payouts/destinations", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/payouts/verify-account", null,
                Map.of("country", "NG", "bankCode", "058", "accountNumber", "0123456789")).status()).isEqualTo(401);
    }

    @Test
    void aVerifiableCountryReturnsTheRealNameWithItsSource() {                      // acceptance 21b
        Resp r = verify(token(), Map.of("country", "NG", "currency", "NGN", "bankCode", "058", "accountNumber", "2000000099"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map()).containsEntry("accountName", "FLUTTERWAVE TEST 0099").containsEntry("source", "FLUTTERWAVE")
                .containsEntry("verified", true).containsEntry("label", "Name verified")
                .containsEntry("accountMasked", "****0099");
        assertThat(r.body().toString()).doesNotContain("2000000099");
    }

    @Test
    void anAccountThePartnerSaysDoesNotExistIsRefusedWithNoFallback() {             // acceptance 21b
        Resp r = verify(token(), Map.of("country", "NG", "bankCode", "058", "accountNumber", "0012345678"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void anUnsupportedCorridorGetsALabelledSimulatedName() {                        // acceptance 21c
        Resp r = verify(token(), Map.of("country", "GB", "currency", "GBP", "bankCode", "200000", "accountNumber", "12345678"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map()).containsEntry("accountName", "TEST ACCOUNT 5678").containsEntry("source", "SIMULATED")
                .containsEntry("verified", false).containsEntry("label", "name not verified (test mode)");
    }

    @Test
    void anUnavailablePartnerGetsTheSimulatedFallbackToo() {                        // acceptance 21c
        Resp r = verify(token(), Map.of("country", "NG", "bankCode", "058", "accountNumber", "1123456789"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map()).containsEntry("source", "SIMULATED").containsEntry("accountName", "TEST ACCOUNT 6789");
    }

    @Test
    void aSimulatedAccountStartingWithTwoZerosIsNotFound() {                        // R-VER-4
        Resp r = verify(token(), Map.of("country", "GB", "bankCode", "200000", "accountNumber", "0012345678"));

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void badInputIsRefusedClearly() {
        String t = token();

        assertThat(verify(t, Map.of("country", "ZZ", "bankCode", "1", "accountNumber", "123456")).code())
                .isEqualTo("COUNTRY_NOT_SUPPORTED");
        assertThat(verify(t, Map.of("country", "GB", "accountNumber", "12345678")).code()).isEqualTo("MISSING_BANK_FIELD");
        assertThat(verify(t, Map.of("country", "NG", "currency", "USD", "bankCode", "058", "accountNumber", "0123456789"))
                .code()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(verify(t, Map.of("country", "NGA", "accountNumber", "0123456789")).status()).isEqualTo(400);
    }
}