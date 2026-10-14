package com.academy.paybridge.account;

import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real HTTP against a real database. The test profile detects every sign-up as Nigeria. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WalletsEndpointTest {

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    record Resp(int status, Object body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body;
        }

        String code() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
        }
    }

    record User(String token, String account) {
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

    User newUser() {
        String email = "wallet-" + UUID.randomUUID() + "@example.com";
        Resp registered = call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test User", "email", email, "password", "password123"));
        assertThat(registered.status()).isEqualTo(201);

        Resp login = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123"));
        assertThat(login.status()).isEqualTo(200);
        String token = (String) ((Map<?, ?>) login.body()).get("token");

        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) ((Map<?, ?>) account.body()).get("accountNumber"));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private Map<String, Object> wallet(User u, String currency) {
        return call(HttpMethod.GET, "/api/v1/wallets", u.token(), null).list().stream()
                .filter(w -> currency.equals(w.get("currency"))).findFirst().orElseThrow();
    }

    @Test
    void aNewCustomerSeesFourEmptyWalletsWithTheHomeWalletFirst() {
        User u = newUser();

        Resp r = call(HttpMethod.GET, "/api/v1/wallets", u.token(), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.list()).extracting(w -> w.get("currency")).containsExactly("NGN", "EUR", "GBP", "USD");
        assertThat(r.list().get(0)).containsEntry("home", true).containsEntry("display", "\u20A60.00");
        assertThat(r.list()).allSatisfy(w -> {
            assertThat(w.get("status")).isEqualTo("ACTIVE");
            assertThat(decimal(w.get("balance"))).isEqualByComparingTo("0");
            assertThat(w.get("accountNumber")).isEqualTo(u.account());
        });
    }

    @Test
    void theWalletListNeedsALogin() {
        assertThat(call(HttpMethod.GET, "/api/v1/wallets", null, null).status()).isEqualTo(401);
    }

    @Test
    void aCustomerOnlySeesTheirOwnWallets() {
        User a = newUser();
        User b = newUser();

        assertThat(call(HttpMethod.GET, "/api/v1/wallets", a.token(), null).list())
                .allSatisfy(w -> assertThat(w.get("accountNumber")).isEqualTo(a.account()));
        assertThat(call(HttpMethod.GET, "/api/v1/wallets", b.token(), null).list())
                .allSatisfy(w -> assertThat(w.get("accountNumber")).isEqualTo(b.account()));
    }

    @Test
    void fundingWithACurrencyShowsOnThatWalletOnly() {
        User u = newUser();

        Resp usd = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("25.50"), "currency", "USD"));
        Resp ngn = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("1000")));                         // no currency means NGN

        assertThat(usd.status()).isEqualTo(200);
        assertThat(ngn.status()).isEqualTo(200);
        assertThat(decimal(wallet(u, "USD").get("balance"))).isEqualByComparingTo("25.50");
        assertThat(wallet(u, "USD")).containsEntry("display", "$25.50");
        assertThat(decimal(wallet(u, "NGN").get("balance"))).isEqualByComparingTo("1000");
        assertThat(decimal(wallet(u, "EUR").get("balance"))).isEqualByComparingTo("0");
    }

    @Test
    void fundingTooManyDecimalsOrAnUnknownCurrencyIsRefused() {
        User u = newUser();

        Resp decimals = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("1.005"), "currency", "USD"));
        Resp unknown = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("5"), "currency", "ZZZ"));

        assertThat(decimals.status()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void theCurrencyListHasUsableCurrenciesOnly() {
        User u = newUser();

        Resp r = call(HttpMethod.GET, "/api/v1/currencies", u.token(), null);

        assertThat(r.status()).isEqualTo(200);
        List<Object> codes = r.list().stream().map(c -> c.get("code")).toList();
        assertThat(codes).contains("NGN", "USD", "JPY").doesNotContain("IRR", "KPW", "SYP", "CUP");
        assertThat(r.list().stream().filter(c -> "JPY".equals(c.get("code"))).findFirst().orElseThrow())
                .containsEntry("minorUnits", 0);
    }

    @Test
    void swapOverHttpFollowsTheCapParkAndSwapBackRules() {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("10.00"), "currency", "EUR"));

        Resp noPark = call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(), Map.of("currency", "JPY"));
        assertThat(noPark.status()).isEqualTo(422);
        assertThat(noPark.code()).isEqualTo("PARK_REQUIRED");

        Resp parkHome = call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", "JPY", "parkCurrency", "NGN"));
        assertThat(parkHome.code()).isEqualTo("WALLET_HOME_FIXED");

        Resp swapped = call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", "JPY", "parkCurrency", "EUR"));
        assertThat(swapped.status()).isEqualTo(200);
        assertThat(swapped.list()).extracting(w -> w.get("currency")).contains("JPY", "EUR");
        assertThat(wallet(u, "EUR")).containsEntry("status", "PARKED");
        assertThat(decimal(wallet(u, "EUR").get("balance"))).isEqualByComparingTo("10.00");

        Resp fundParked = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal("5.00"), "currency", "EUR"));
        assertThat(fundParked.status()).isEqualTo(200);

        Resp back = call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", "EUR", "parkCurrency", "JPY"));
        assertThat(back.status()).isEqualTo(200);
        assertThat(wallet(u, "EUR")).containsEntry("status", "ACTIVE");
        assertThat(decimal(wallet(u, "EUR").get("balance"))).isEqualByComparingTo("15.00");
    }
}