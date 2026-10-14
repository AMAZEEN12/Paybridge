package com.academy.paybridge.fx;

import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.client.StubRateProvider;
import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.domain.QuoteStatus;
import com.academy.paybridge.fx.repository.QuoteRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "paybridge.conversion.enabled=true")
@ActiveProfiles("test")
class ConversionFlowTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    QuoteRepository quoteRepository;

    @Autowired
    RateProvider rateProvider;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    record Resp(int status, Object body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body;
        }

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
            }
            return new Resp(response.getStatusCode().value(), parsed);
        });
    }

    User newUser() {
        String email = "conv-" + UUID.randomUUID() + "@example.com";
        Resp registered = call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Conversion Tester", "email", email, "password", "password123"));
        assertThat(registered.status()).isEqualTo(201);

        Resp login = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", "password123"));
        assertThat(login.status()).isEqualTo(200);
        String token = (String) login.map().get("token");

        Resp account = call(HttpMethod.POST, "/api/v1/accounts", token, null);
        assertThat(account.status()).isEqualTo(200);
        return new User(token, (String) account.map().get("accountNumber"));
    }

    private Map<String, Object> wallet(User u, String currency) {
        return call(HttpMethod.GET, "/api/v1/wallets", u.token(), null).list().stream()
                .filter(w -> currency.equals(w.get("currency"))).findFirst().orElseThrow();
    }

    private BigDecimal balance(User u, String currency) {
        return new BigDecimal(String.valueOf(wallet(u, currency).get("balance")));
    }

    @Test
    void acceptance1_nonProgrammerTest_convertNgnToUsd() {
        // Register (test country Nigeria) -> gets NGN, USD, EUR, GBP
        User u = newUser();

        // Fund NGN with 50,000 (dev funding)
        Resp fund = call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "50000.00", "currency", "NGN"));
        assertThat(fund.status()).isEqualTo(200);
        assertThat(balance(u, "NGN")).isEqualByComparingTo("50000.00");
        assertThat(balance(u, "USD")).isEqualByComparingTo("0.00");

        // Request conversion quote: 10,000 NGN to USD
        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "10000.00"));
        assertThat(quoteResp.status()).isEqualTo(200);

        Map<String, Object> quote = quoteResp.map();
        UUID quoteId = UUID.fromString((String) quote.get("id"));
        assertThat(quote.get("sourceCurrency")).isEqualTo("NGN");
        assertThat(quote.get("targetCurrency")).isEqualTo("USD");
        assertThat(new BigDecimal(String.valueOf(quote.get("sourceAmount")))).isEqualByComparingTo("10000.00");
        // Free promo for new user: fee is 0.00, feeReason is PROMO
        assertThat(new BigDecimal(String.valueOf(quote.get("fee")))).isEqualByComparingTo("0.00");
        assertThat(quote.get("feeReason")).isEqualTo("PROMO");
        // Target: 10,000 / 1500 = 6.6666... floored to 6.66
        assertThat(new BigDecimal(String.valueOf(quote.get("targetAmount")))).isEqualByComparingTo("6.66");
        assertThat(quote.get("rateLabel")).isEqualTo("indicative rate");

        // Confirm conversion
        Resp confirm = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(confirm.status()).isEqualTo(200);
        assertThat(confirm.map().get("status")).isEqualTo("COMPLETED");
        assertThat(confirm.map().get("journalKey")).isEqualTo("CONV:" + quoteId);

        // NGN balance drops by exactly 10,000 to 40,000; USD rises by exactly 6.66 to 6.66
        assertThat(balance(u, "NGN")).isEqualByComparingTo("40000.00");
        assertThat(balance(u, "USD")).isEqualByComparingTo("6.66");

        // Acceptance 8: Ledger balances per currency and reconciliation is clean
        ReconciliationReport report = reconciliation.run();
        assertThat(report.ok()).isTrue();
    }


    @Test
    void acceptance9_quoteLock_expiredQuoteIsRejected() {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "20000.00", "currency", "NGN"));

        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "5000.00"));
        assertThat(quoteResp.status()).isEqualTo(200);
        UUID quoteId = UUID.fromString((String) quoteResp.map().get("id"));

        // Simulate expiry in database (> 60 seconds)
        Quote q = quoteRepository.findById(quoteId).orElseThrow();
        quoteRepository.save(new Quote(q.getId(), q.getCustomerId(), q.getType(),
                q.getSourceCurrency(), q.getTargetCurrency(), q.getSourceAmountMinor(),
                q.getFeeMinor(), q.getFeeReason(), q.getNetMinor(), q.getTargetAmountMinor(),
                q.getRate(), q.getSnapshotId(), q.getUsdEquivalentMinor(),
                q.getStatus(), Instant.now().minusSeconds(10), q.getCreatedAt()));

        // Confirming expired quote fails
        Resp confirm = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(confirm.status()).isEqualTo(422);
        assertThat(confirm.code()).isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    void acceptance10_singleUse_confirmingTwiceFailsSecondTime() {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "20000.00", "currency", "NGN"));

        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "5000.00"));
        UUID quoteId = UUID.fromString((String) quoteResp.map().get("id"));

        // First confirm succeeds
        Resp first = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(first.status()).isEqualTo(200);

        // Second confirm fails
        Resp second = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("QUOTE_ALREADY_USED");
    }

    @Test
    void acceptance10_concurrentConfirmsConvertOnce() throws InterruptedException {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "20000.00", "currency", "NGN"));

        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "5000.00"));
        UUID quoteId = UUID.fromString((String) quoteResp.map().get("id"));

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Resp r = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                            Map.of("quoteId", quoteId.toString()));
                    if (r.status() == 200) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }

    @Test
    void acceptance11_failedQuote_leavesBalancesUnchangedAndCannotBeReused() {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "5000.00", "currency", "NGN"));

        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "5000.00"));
        UUID quoteId = UUID.fromString((String) quoteResp.map().get("id"));

        // User freezes account or spends money in another transaction
        // Set account frozen
        call(HttpMethod.POST, "/api/v1/accounts/" + u.account() + "/freeze", u.token(), null);

        // Attempt confirm -> fails because account is frozen
        Resp confirm = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(confirm.status()).isEqualTo(422);

        // Quote is now marked FAILED
        Quote q = quoteRepository.findById(quoteId).orElseThrow();
        assertThat(q.getStatus()).isEqualTo(QuoteStatus.FAILED);

        // Unfreeze account
        call(HttpMethod.POST, "/api/v1/accounts/" + u.account() + "/unfreeze", u.token(), null);

        // Re-confirming failed quote is refused
        Resp retry = call(HttpMethod.POST, "/api/v1/conversions", u.token(),
                Map.of("quoteId", quoteId.toString()));
        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.code()).isEqualTo("QUOTE_FAILED");

        // Balances remain untouched
        assertThat(balance(u, "NGN")).isEqualByComparingTo("5000.00");
        assertThat(balance(u, "USD")).isEqualByComparingTo("0.00");
    }

    @Test
    void acceptance14_limits_abovePerConversionLimitIsRejected() {
        User u = newUser();
        // Limit is $1,000 USD equivalent (at 1500 NGN/USD, $1000 = 1,500,000 NGN)
        // Fund 2,000,000 NGN
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "2000000.00", "currency", "NGN"));

        // Attempt to convert 1,600,000 NGN ($1,066.66 USD equivalent > $1000)
        Resp quoteResp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "1600000.00"));
        assertThat(quoteResp.status()).isEqualTo(422);
        assertThat(quoteResp.code()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void parkedWalletCannotBeConverted() {
        User u = newUser();
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "50000.00", "currency", "NGN"));

        // Park EUR wallet by swapping in JPY
        call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", "JPY", "parkCurrency", "EUR"));

        // Attempt conversion out of parked EUR wallet -> rejected
        Resp convOut = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "EUR", "targetCurrency", "USD", "amount", "10.00"));
        assertThat(convOut.status()).isEqualTo(422);
        assertThat(convOut.code()).isEqualTo("WALLET_PARKED");

        // Attempt conversion into parked EUR wallet -> rejected
        Resp convIn = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "EUR", "amount", "1000.00"));
        assertThat(convIn.status()).isEqualTo(422);
        assertThat(convIn.code()).isEqualTo("WALLET_PARKED");
    }

    @Test
    void oppositeDirectionConversionsDoNotDeadlock() throws InterruptedException {
        User u = newUser();
        // Fund NGN with 50,000 and USD with 50
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "50000.00", "currency", "NGN"));
        call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", "50.00", "currency", "USD"));

        Resp q1Resp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "NGN", "targetCurrency", "USD", "amount", "1000.00"));
        Resp q2Resp = call(HttpMethod.POST, "/api/v1/conversions/quotes", u.token(),
                Map.of("sourceCurrency", "USD", "targetCurrency", "NGN", "amount", "1.00"));

        UUID q1 = UUID.fromString((String) q1Resp.map().get("id"));
        UUID q2 = UUID.fromString((String) q2Resp.map().get("id"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();

        executor.submit(() -> {
            try {
                startLatch.await();
                Resp r = call(HttpMethod.POST, "/api/v1/conversions", u.token(), Map.of("quoteId", q1.toString()));
                if (r.status() == 200) success.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                Resp r = call(HttpMethod.POST, "/api/v1/conversions", u.token(), Map.of("quoteId", q2.toString()));
                if (r.status() == 200) success.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(success.get()).isEqualTo(2);

        ReconciliationReport report = reconciliation.run();
        assertThat(report.ok()).isTrue();
    }
}

