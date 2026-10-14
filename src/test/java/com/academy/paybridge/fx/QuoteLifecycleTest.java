package com.academy.paybridge.fx;

import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.repository.QuoteRepository;
import com.academy.paybridge.fx.service.QuoteService;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Quote life cycle rules: only the owner can lock a quote, a failed confirm always ends FAILED with a reason,
 * and the clean-up job expires and fails quotes without ever touching a completed one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "paybridge.conversion.enabled=true")
@ActiveProfiles("test")
class QuoteLifecycleTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    QuoteRepository quoteRepository;
    @Autowired
    QuoteService quoteService;
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

        String code() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
        }
    }

    record User(String token, String account) {
    }

    Resp call(HttpMethod method, String path, String token, Object body) {
        RestClient client = RestClient.create("http://localhost:" + port);
        var spec = client.method(method).uri(path).headers(h -> {
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
        String email = "quote-life-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Quote Tester", "email", email, "password", "password123")).status())
                .isEqualTo(201);
        Resp login = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123"));
        String token = (String) login.map().get("token");
        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        return new User(token, (String) account.map().get("accountNumber"));
    }

    private void fund(User u) {
        Resp r = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "20000.00", "currency", "NGN"));
        assertThat(r.status()).isEqualTo(200);
    }

    private UUID quote(User u) {
        Resp r = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "5000.00"));
        assertThat(r.status()).isEqualTo(200);
        return UUID.fromString((String) r.map().get("id"));
    }

    private Resp confirm(User u, UUID quoteId) {
        return call(HttpMethod.POST, "/api/v1/conversions", u.token(), Map.of("quoteId", quoteId.toString()));
    }

    private QuoteStatus status(UUID id) {
        return quoteRepository.findById(id).orElseThrow().getStatus();
    }

    private Long ownerOf(UUID id) {
        return quoteRepository.findById(id).orElseThrow().getCustomerId();
    }

    private void setExpiry(UUID id, Instant expiresAt) {
        jdbc.update("update quotes set expires_at = ? where id = ?", Timestamp.from(expiresAt), id);
    }

    // ---- fix 3: only the owner can move a quote to EXECUTING

    @Test
    void anotherCustomerCannotLockSomeoneElsesQuote() {
        User owner = newUser();
        User other = newUser();
        fund(owner);
        UUID id = quote(owner);

        Resp attempt = confirm(other, id);
        assertThat(attempt.status()).isEqualTo(404);
        assertThat(attempt.code()).isEqualTo("QUOTE_NOT_FOUND");
        assertThat(status(id)).isEqualTo(QuoteStatus.ACTIVE);          // not pushed to EXECUTING

        // the same rule holds at the repository level
        assertThat(quoteRepository.moveToExecuting(id, ownerOf(id) + 1_000_000, Instant.now())).isZero();
        assertThat(status(id)).isEqualTo(QuoteStatus.ACTIVE);

        // and the owner can still use it
        Resp ok = confirm(owner, id);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.map().get("status")).isEqualTo("COMPLETED");
    }

    // ---- fix 1: any failure after EXECUTING ends as FAILED, with a reason and an audit line

    @Test
    void aFailedConfirmEndsFailedWithItsReasonAndIsAudited() {
        User u = newUser();
        fund(u);
        UUID id = quote(u);

        assertThat(call(HttpMethod.POST, "/api/v1/accounts/" + u.account() + "/freeze", u.token(), null).status())
                .isEqualTo(200);

        Resp refused = confirm(u, id);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("ACCOUNT_FROZEN");

        Quote q = quoteRepository.findById(id).orElseThrow();
        assertThat(q.getStatus()).isEqualTo(QuoteStatus.FAILED);
        assertThat(q.getFailureReason()).isEqualTo("ACCOUNT_FROZEN");

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_log where action = 'CONVERSION_FAILED' and reference = ?",
                Integer.class, id.toString());
        assertThat(audited).isEqualTo(1);

        // a failed quote is spent
        Resp retry = confirm(u, id);
        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.code()).isEqualTo("QUOTE_FAILED");
    }

    // ---- fix 4: EXPIRED status, and clean-up never touches a COMPLETED quote

    @Test
    void cleanupExpiresOverdueQuotesAndLeavesCompletedOnesAlone() {
        User u = newUser();
        fund(u);
        UUID overdue = quote(u);
        UUID done = quote(u);
        assertThat(confirm(u, done).status()).isEqualTo(200);

        int expired = quoteRepository.expireOverdue(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(expired).isGreaterThanOrEqualTo(1);

        assertThat(status(overdue)).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(status(done)).isEqualTo(QuoteStatus.COMPLETED);

        Resp late = confirm(u, overdue);
        assertThat(late.status()).isEqualTo(422);
        assertThat(late.code()).isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    void aConversionRunningJustAfterItsExpiryIsNotStuckButOldOnesAre() {
        User u = newUser();
        fund(u);
        UUID running = quote(u);
        UUID stuck = quote(u);
        UUID finished = quote(u);
        Long ownerId = ownerOf(running);

        assertThat(confirm(u, finished).status()).isEqualTo(200);                          // COMPLETED
        assertThat(quoteRepository.moveToExecuting(running, ownerId, Instant.now())).isEqualTo(1);
        assertThat(quoteRepository.moveToExecuting(stuck, ownerId, Instant.now())).isEqualTo(1);

        setExpiry(running, Instant.now().minusSeconds(60));              // confirmed just before expiry, still running
        setExpiry(stuck, Instant.now().minus(Duration.ofMinutes(6)));    // past the 5 minute stuck limit
        setExpiry(finished, Instant.now().minus(Duration.ofMinutes(10)));// old, but completed

        quoteService.cleanupQuotes();

        assertThat(status(running)).isEqualTo(QuoteStatus.EXECUTING);    // the old 60 second rule would have failed it
        Quote failed = quoteRepository.findById(stuck).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(QuoteStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("STUCK_EXECUTING");
        assertThat(status(finished)).isEqualTo(QuoteStatus.COMPLETED);   // never overwritten

        quoteRepository.markFailed(running, "TEST_CLEANUP");             // leave nothing EXECUTING behind
    }
}