package com.academy.paybridge.transfer;

import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step A2 (R-LKP, acceptance 4). Real HTTP against a real database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecipientLookupTest {

    @Value("${local.server.port}")
    int port;

    @Value("${paybridge.resolve-limit-per-minute:10}")
    int limitPerMinute;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    record Resp(int status, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }

        @SuppressWarnings("unchecked")
        List<String> currencies() {
            return (List<String>) map().get("receivableCurrencies");
        }

        String code() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
        }
    }

    record User(String token, String account, String name) {
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

    User newUser(String fullName) {
        String email = "lookup-" + UUID.randomUUID() + "@example.com";
        Resp registered = call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", fullName, "email", email, "password", "password123"));
        assertThat(registered.status()).isEqualTo(201);
        Resp login = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123"));
        String token = (String) login.map().get("token");
        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) account.map().get("accountNumber"), fullName);
    }

    Resp lookup(User asker, String accountNumber) {
        return call(HttpMethod.GET, "/api/v1/recipients/" + accountNumber, asker.token(), null);
    }

    @Test
    void returnsTheNameAndTheReceivableCurrenciesAndNothingElse() {
        User a = newUser("Ada Sender");
        User b = newUser("Bola Receiver");

        Resp r = lookup(a, b.account());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map()).containsOnlyKeys("accountNumber", "accountName", "receivableCurrencies");   // no balances, status or owner id
        assertThat(r.map()).containsEntry("accountNumber", b.account()).containsEntry("accountName", "Bola Receiver");
        assertThat(r.currencies()).containsExactly("EUR", "GBP", "NGN", "USD");
    }

    @Test
    void aParkedWalletIsStillListed() {
        User a = newUser("Ada Sender");
        User b = newUser("Bola Receiver");
        // Four wallets are active, so a fifth currency needs one parked: park EUR, activate JPY.
        Resp swap = call(HttpMethod.POST, "/api/v1/wallets/swap", b.token(),
                Map.of("currency", "JPY", "parkCurrency", "EUR"));
        assertThat(swap.status()).isEqualTo(200);

        Resp r = lookup(a, b.account());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.currencies()).containsExactly("EUR", "GBP", "JPY", "NGN", "USD");   // EUR is parked and still there
    }

    @Test
    void anExcludedCurrencyIsNotListed() {
        User a = newUser("Ada Sender");
        User b = newUser("Bola Receiver");
        // An excluded currency cannot be activated through the API, so plant the wallet directly.
        jdbc.update("insert into wallets (account_id, currency, status, is_home) "
                + "select id, 'IRR', 'ACTIVE', false from accounts where account_number = ?", b.account());
        try {
            Resp r = lookup(a, b.account());

            assertThat(r.status()).isEqualTo(200);
            assertThat(r.currencies()).containsExactly("EUR", "GBP", "NGN", "USD").doesNotContain("IRR");
        } finally {
            jdbc.update("delete from wallets where currency = 'IRR' and account_id = "
                    + "(select id from accounts where account_number = ?)", b.account());   // keep reconciliation tests clean
        }
    }

    @Test
    void aFrozenAccountCanStillBeLookedUp() {
        User a = newUser("Ada Sender");
        User b = newUser("Bola Receiver");
        assertThat(call(HttpMethod.POST, "/api/v1/accounts/" + b.account() + "/freeze", b.token(), null).status())
                .isEqualTo(200);

        Resp r = lookup(a, b.account());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.currencies()).containsExactly("EUR", "GBP", "NGN", "USD");
    }

    @Test
    void aCustomerCanLookUpTheirOwnNumber() {
        User a = newUser("Ada Sender");

        Resp r = lookup(a, a.account());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map()).containsEntry("accountName", "Ada Sender");
        assertThat(r.currencies()).containsExactly("EUR", "GBP", "NGN", "USD");
    }

    @Test
    void anUnknownNumberASystemAccountAndANonNumberAreAllNotFound() {
        User a = newUser("Ada Sender");

        for (String number : List.of("1234567890", "9000000001", "abc")) {
            Resp r = lookup(a, number);
            assertThat(r.status()).as(number).isEqualTo(404);
            assertThat(r.code()).as(number).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void lookupNeedsALogin() {
        assertThat(call(HttpMethod.GET, "/api/v1/recipients/1234567890", null, null).status()).isEqualTo(401);
    }

    @Test
    void lookupsAreLimitedPerCustomerPerMinute() {
        User a = newUser("Ada Sender");
        User b = newUser("Bola Receiver");
        User other = newUser("Chi Other");

        for (int i = 0; i < limitPerMinute; i++) {
            assertThat(lookup(a, b.account()).status()).as("lookup " + (i + 1)).isEqualTo(200);
        }
        Resp refused = lookup(a, b.account());

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("TOO_MANY_LOOKUPS");
        assertThat(lookup(other, b.account()).status()).isEqualTo(200);     // the limit is per customer
    }
}