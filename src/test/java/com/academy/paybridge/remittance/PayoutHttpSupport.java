package com.academy.paybridge.remittance;

import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Helpers for the payout tests: real HTTP against the running app, the fake partner behind it. */
abstract class PayoutHttpSupport {

    static final String PIN = "4821";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

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

    record User(String token, String account, String email) {
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

    /** A new customer with a PIN and an account. {@code country} is written straight to the customer row. */
    User newUser(String country) {
        String email = "po-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test Payer", "email", email, "password", "password123")).status()).isEqualTo(201);
        jdbc.update("update customers set country = ? where email = ?", country, email);
        String token = (String) call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123")).map().get("token");
        assertThat(call(HttpMethod.POST, "/api/v1/customers/me/pin", token, Map.of("pin", PIN)).status()).isEqualTo(204);
        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) account.map().get("accountNumber"), email);
    }

    void fund(User u, String currency, String amount) {
        assertThat(call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal(amount), "currency", currency)).status()).isEqualTo(200);
    }

    BigDecimal balance(User u, String currency) {
        Map<String, Object> w = call(HttpMethod.GET, "/api/v1/wallets", u.token(), null).list().stream()
                .filter(x -> x.get("accountNumber").equals(u.account()) && x.get("currency").equals(currency))
                .findFirst().orElseThrow();
        return new BigDecimal(String.valueOf(w.get("balance")));
    }

    Resp quote(User u, String source, String amount, String country, String bank, String account, String purpose) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceCurrency", source);
        body.put("amount", new BigDecimal(amount));
        body.put("country", country);
        if (bank != null) {
            body.put("bankCode", bank);
        }
        body.put("accountNumber", account);
        body.put("recipientType", "INDIVIDUAL");
        body.put("purpose", purpose);
        return call(HttpMethod.POST, "/api/v1/payouts/quotes", u.token(), body);
    }

    Resp confirm(User u, Object quoteId, String pin, String key) {
        return call(HttpMethod.POST, "/api/v1/payouts", u.token(), Map.of("Idempotency-Key", key),
                Map.of("quoteId", String.valueOf(quoteId), "pin", pin));
    }

    Resp sameCurrencyTransfer(User from, User to, String currency, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceAccountNumber", from.account());
        body.put("destinationAccountNumber", to.account());
        body.put("amount", new BigDecimal(amount));
        body.put("currency", currency);
        body.put("pin", PIN);
        return call(HttpMethod.POST, "/api/v1/transfers/internal", from.token(), Map.of("Idempotency-Key", key()), body);
    }

    static String key() {
        return "k-" + UUID.randomUUID();
    }

    static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    boolean notified(User u, String text) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<String> messages = new ArrayList<>();
            call(HttpMethod.GET, "/api/v1/notifications", u.token(), null).list()
                    .forEach(n -> messages.add(String.valueOf(n.get("message"))));
            if (messages.stream().anyMatch(m -> m.contains(text))) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    void assertBooksBalance() {
        ReconciliationReport report = reconciliation.run();
        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
    }
}