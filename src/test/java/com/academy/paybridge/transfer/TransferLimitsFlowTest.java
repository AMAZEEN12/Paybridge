package com.academy.paybridge.transfer;

import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step A4 (R-LIM2-1 to 5, acceptance 7 and 21d). Real HTTP, real database, fixed test rates. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransferLimitsFlowTest {

    private static final String PIN = "4821";

    @Value("${local.server.port}")
    int port;

    @Autowired
    ReconciliationService reconciliation;

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
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body;
        }

        String code() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
        }

        String message() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : null;
        }
    }

    record User(String token, String account) {
    }

    Resp call(HttpMethod method, String path, String token, Map<String, String> headers, Object body) {
        RestClient client = RestClient.create("http://localhost:" + port);
        RestClient.RequestBodySpec spec = client.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
            if (headers != null) {
                headers.forEach(h::set);
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

    Resp call(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, null, body);
    }

    User newUser() {
        String email = "lim-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test " + email.substring(4, 10), "email", email, "password", "password123"))
                .status()).isEqualTo(201);
        String token = (String) call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123")).map().get("token");
        assertThat(call(HttpMethod.POST, "/api/v1/customers/me/pin", token, Map.of("pin", PIN)).status())
                .isEqualTo(204);
        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) account.map().get("accountNumber"));
    }

    void fund(User u, String currency, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        if (currency != null) {
            body.put("currency", currency);
        }
        assertThat(call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(), body).status())
                .isEqualTo(200);
    }

    Resp send(User from, User to, String currency, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceAccountNumber", from.account());
        body.put("destinationAccountNumber", to.account());
        body.put("amount", new BigDecimal(amount));
        body.put("pin", PIN);
        if (currency != null) {
            body.put("currency", currency);
        }
        return call(HttpMethod.POST, "/api/v1/transfers/internal", from.token(),
                Map.of("Idempotency-Key", "k-" + UUID.randomUUID()), body);
    }

    void assertBooksBalance() {
        ReconciliationReport report = reconciliation.run();
        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
    }

    @Test
    void aSingleUsdTransferOverTheLimitIsRefusedAndNothingMoves() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "3000");

        Resp over = send(a, b, "USD", "1000.01");

        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(over.message()).contains("single transfer limit");
        assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);            // exactly the limit passes
        assertBooksBalance();
    }

    @Test
    void theDailyTotalIsFiveThousandDollarsAcrossTransfers() {                    // acceptance 21d (transfers part)
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "6000");

        for (int i = 0; i < 5; i++) {
            assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);
        }
        Resp sixth = send(a, b, "USD", "1");

        assertThat(sixth.status()).isEqualTo(422);
        assertThat(sixth.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(sixth.message()).contains("daily limit");
        assertBooksBalance();
    }

    @Test
    void theDailyTotalIsInUsdSoDifferentCurrenciesShareIt() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "5000");
        fund(a, "EUR", "5000");

        for (int i = 0; i < 4; i++) {
            assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);        // $4,000 used
        }
        Resp euro = send(a, b, "EUR", "1000");                                    // worth more than $1,000 at test rates

        assertThat(euro.status()).isEqualTo(422);
        assertThat(euro.code()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void anotherCustomersTransfersDoNotCountTowardMyTotal() {
        User a = newUser();
        User c = newUser();
        User b = newUser();
        fund(a, "USD", "5000");
        fund(c, "USD", "5000");

        for (int i = 0; i < 5; i++) {
            assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);
        }

        assertThat(send(c, b, "USD", "1000").status()).isEqualTo(201);            // c starts from zero
    }

    @Test
    void nairaToNairaKeepsItsOwnLimitsAndIsNotCountedInDollars() {                // R-LIM2-1
        User a = newUser();
        User b = newUser();
        fund(a, null, "400000");
        fund(a, "USD", "5000");

        assertThat(send(a, b, null, "300000").status()).isEqualTo(201);           // large in NGN, no dollar limit applies
        for (int i = 0; i < 5; i++) {
            assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);        // the NGN transfer did not use any of it
        }
    }

    @Test
    void aRefusedTransferCountsForNothing() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "5000");

        assertThat(send(a, b, "USD", "1001").status()).isEqualTo(422);            // refused, so it must not use the day
        for (int i = 0; i < 5; i++) {
            assertThat(send(a, b, "USD", "1000").status()).isEqualTo(201);
        }
    }
}