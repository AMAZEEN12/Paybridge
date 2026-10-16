package com.academy.paybridge.settlement;

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

/**
 * Plan 002-004, step C1 (R-SET-1, R-SET-2, R-SET-12; acceptance 22 and 28). The key and one threshold are set here:
 * the NGN pool must hold at least 0, and a cross-currency transfer into naira takes it below that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "paybridge.settlement.admin-key=test-settlement-key",
        "paybridge.settlement.thresholds[0].account=EXT:FX_POOL:NGN",
        "paybridge.settlement.thresholds[0].min-minor=0"})
@ActiveProfiles("test")
class SettlementPositionsTest {

    private static final String PIN = "4821";
    private static final String KEY = "test-settlement-key";
    private static final String PATH = "/api/v1/admin/settlement/positions";

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

    User newUser() {
        String email = "sp-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null, null,
                Map.of("fullName", "Test " + email.substring(3, 9), "email", email, "password", "password123"))
                .status()).isEqualTo(201);
        String token = (String) call(HttpMethod.POST, "/api/v1/auth/login", null, null,
                Map.of("email", email, "password", "password123")).map().get("token");
        assertThat(call(HttpMethod.POST, "/api/v1/customers/me/pin", token, null, Map.of("pin", PIN)).status())
                .isEqualTo(204);
        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) account.map().get("accountNumber"));
    }

    void fund(User u, String currency, String amount) {
        assertThat(call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(), null,
                Map.of("amount", new BigDecimal(amount), "currency", currency)).status()).isEqualTo(200);
    }

    /** USD to NGN between two customers: quote, then confirm. Returns the confirm response and the naira received. */
    Resp send(User from, User to, String amount) {
        Resp q = call(HttpMethod.POST, "/api/v1/transfers/cross-currency/quotes", from.token(), null,
                Map.of("destinationAccountNumber", to.account(), "sourceCurrency", "USD",
                        "receiveCurrency", "NGN", "amount", new BigDecimal(amount)));
        assertThat(q.status()).isEqualTo(200);
        return call(HttpMethod.POST, "/api/v1/transfers/cross-currency", from.token(),
                Map.of("Idempotency-Key", "k-" + UUID.randomUUID()),
                Map.of("quoteId", String.valueOf(q.map().get("id")), "pin", PIN));
    }

    Resp positions(String token) {
        return call(HttpMethod.GET, PATH, token, Map.of("X-Admin-Key", KEY), null);
    }

    /** The position, or null if that currency is not in the report yet. */
    @SuppressWarnings("unchecked")
    Map<String, Object> position(Resp r, String currency, String account) {
        for (Map<String, Object> c : (List<Map<String, Object>>) r.map().get("currencies")) {
            if (c.get("currency").equals(currency)) {
                for (Map<String, Object> p : (List<Map<String, Object>>) c.get("positions")) {
                    if (p.get("account").equals(account)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    long net(Resp r, String currency, String account) {
        Map<String, Object> p = position(r, currency, account);
        return p == null ? 0L : ((Number) p.get("netMinor")).longValue();
    }

    @Test
    void everySettlementEndpointNeedsTheOperatorKey() {                           // acceptance 28
        String token = newUser().token();

        assertThat(call(HttpMethod.GET, PATH, null, Map.of("X-Admin-Key", KEY), null).status()).isEqualTo(401);
        Resp noKey = call(HttpMethod.GET, PATH, token, null, null);
        assertThat(noKey.status()).isEqualTo(403);
        assertThat(noKey.code()).isEqualTo("ADMIN_KEY_REQUIRED");
        Resp wrong = call(HttpMethod.GET, PATH, token, Map.of("X-Admin-Key", "not-the-key"), null);
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(positions(token).status()).isEqualTo(200);
    }

    @Test
    void theReportListsEveryPositionAccountForNaira() {                           // acceptance 22
        Resp r = positions(newUser().token());

        assertThat(r.status()).isEqualTo(200);
        for (String account : List.of("EXT:FX_POOL:NGN", "EXT:FX_FEE:NGN", "EXT:PAYOUT_FEE:NGN", "EXT:RATE_DIFF:NGN",
                "EXT:IN_FLIGHT", "EXT:PAID_OUT", "EXT:REVENUE:NGN", "EXT:TAX_PAYABLE:NGN",
                "9000000001:NGN", "9000000002:NGN", "9000000003:NGN")) {
            assertThat(position(r, "NGN", account)).as(account).isNotNull();
        }
    }

    @Test
    void positionsComeFromTheLedgerAndAPoolBelowItsMinimumIsFlaggedButNobodyIsBlocked() {   // acceptance 22
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "200");
        Resp before = positions(a.token());

        Resp first = send(a, b, "50");
        assertThat(first.status()).isEqualTo(201);
        long receivedKobo = new BigDecimal(String.valueOf(first.map().get("targetAmount"))).movePointRight(2).longValueExact();
        Resp after = positions(a.token());

        // The USD pool took the net (fee is 0 in the first 30 days); the NGN pool gave out what B received.
        assertThat(net(after, "USD", "EXT:FX_POOL:USD") - net(before, "USD", "EXT:FX_POOL:USD")).isEqualTo(5_000L);
        assertThat(net(after, "NGN", "EXT:FX_POOL:NGN") - net(before, "NGN", "EXT:FX_POOL:NGN")).isEqualTo(-receivedKobo);

        // The NGN pool is now below its minimum of 0: flagged, with a warning, and nothing is refused.
        Map<String, Object> ngnPool = position(after, "NGN", "EXT:FX_POOL:NGN");
        assertThat(ngnPool.get("belowThreshold")).isEqualTo(true);
        assertThat(((Number) ngnPool.get("thresholdMinor")).longValue()).isZero();
        assertThat(String.valueOf(after.map().get("warnings"))).contains("EXT:FX_POOL:NGN");
        assertThat(position(after, "USD", "EXT:FX_POOL:USD").get("belowThreshold")).isEqualTo(false);
        assertThat(send(a, b, "10").status()).isEqualTo(201);
    }
}