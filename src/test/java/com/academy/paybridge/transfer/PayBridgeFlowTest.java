package com.academy.paybridge.transfer;

import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.audit.AuditLogRepository;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End to end tests against a real Postgres database, through the real HTTP API.
 * They skip themselves if no database named paybridge_test is reachable (see src/test/resources/application-test.yml).
 *
 * Create it once:   createdb paybridge_test
 * Do NOT put @Transactional on a test class like this one: the rollback and lock tests need real commits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PayBridgeFlowTest {

    private static final String PIN = "1234";

    @Value("${local.server.port}")
    int port;

    @Autowired
    AccountRepository accountRepository;
    @Autowired
    AuditLogRepository auditRepository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one named paybridge_test to run these tests.");
    }

    // ------------------------------------------------------------------ tiny HTTP helper

    record Resp(int status, Map<String, Object> body, Map<String, String> headers) {
        String code() {
            return body == null ? null : String.valueOf(body.get("code"));
        }
    }

    @SuppressWarnings("unchecked")
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
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            Map<String, String> h = new LinkedHashMap<>();
            res.getHeaders().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            Map<String, Object> parsed = null;
            try {
                parsed = res.bodyTo(Map.class);
            } catch (RuntimeException ignored) {
                // empty body, for example 204
            }
            return new Resp(res.getStatusCode().value(), parsed, h);
        });
    }

    Resp post(String path, String token, Map<String, String> headers, Object body) {
        return call(HttpMethod.POST, path, token, headers, body);
    }

    Resp get(String path, String token) {
        return call(HttpMethod.GET, path, token, null, null);
    }

    record User(String token, String account) {
    }

    /** Registers a customer, logs in, sets a PIN, opens an account and funds it. */
    User newUser(String fundNaira) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        assertThat(post("/api/v1/auth/register", null, null,
                Map.of("fullName", "Test User", "email", email, "password", "password123")).status()).isEqualTo(201);
        Resp login = post("/api/v1/auth/login", null, null, Map.of("email", email, "password", "password123"));
        assertThat(login.status()).isEqualTo(200);
        String token = (String) login.body().get("token");
        assertThat(post("/api/v1/customers/me/pin", token, null, Map.of("pin", PIN)).status()).isEqualTo(204);
        Resp opened = post("/api/v1/accounts", token, null, null);
        assertThat(opened.status()).isEqualTo(201);
        String account = (String) opened.body().get("accountNumber");
        if (new BigDecimal(fundNaira).signum() > 0) {
            assertThat(post("/api/v1/dev/accounts/" + account + "/fund", token, null,
                    Map.of("amount", new BigDecimal(fundNaira))).status()).isEqualTo(200);
        }
        return new User(token, account);
    }

    BigDecimal balance(User u) {
        Resp r = get("/api/v1/accounts/" + u.account(), u.token());
        return new BigDecimal(String.valueOf(r.body().get("balance")));
    }

    long systemBalanceKobo(String number) {
        return accountRepository.findByAccountNumber(number).orElseThrow().getBalanceKobo();
    }

    Resp internal(User from, String to, String amount, String key) {
        return post("/api/v1/transfers/internal", from.token(), Map.of("Idempotency-Key", key),
                Map.of("sourceAccountNumber", from.account(), "destinationAccountNumber", to,
                        "amount", new BigDecimal(amount), "pin", PIN));
    }

    Resp external(User from, String bankCode, String destAccount, String amount, String key) {
        return post("/api/v1/transfers/external", from.token(), Map.of("Idempotency-Key", key),
                Map.of("sourceAccountNumber", from.account(), "bankCode", bankCode,
                        "destinationAccountNumber", destAccount, "amount", new BigDecimal(amount), "pin", PIN));
    }

    String key() {
        return "key-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------ internal transfers

    @Test
    void internalTransferMovesMoneyAndCollectsStampDuty() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        long stampBefore = systemBalanceKobo("9000000003");

        Resp r = internal(ada, tunde.account(), "20000", key());

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("status")).isEqualTo("SUCCESSFUL");
        assertThat(balance(ada)).isEqualByComparingTo("29950.00");     // 50,000 - 20,000 - 50 stamp duty
        assertThat(balance(tunde)).isEqualByComparingTo("20000.00");
        assertThat(systemBalanceKobo("9000000003") - stampBefore).isEqualTo(5000L);
    }

    @Test
    void sameKeyAndSameRequestReturnsTheSameTransferWithoutMovingMoneyTwice() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        String key = key();

        Resp first = internal(ada, tunde.account(), "5000", key);
        Resp second = internal(ada, tunde.account(), "5000", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.headers().get("idempotent-replay")).isEqualTo("true");
        assertThat(second.body().get("reference")).isEqualTo(first.body().get("reference"));
        assertThat(balance(ada)).isEqualByComparingTo("45000.00");
    }

    @Test
    void sameKeyWithADifferentAmountIsRefused() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        String key = key();
        assertThat(internal(ada, tunde.account(), "5000", key).status()).isEqualTo(201);

        Resp second = internal(ada, tunde.account(), "9000", key);

        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(ada)).isEqualByComparingTo("45000.00");
    }

    @Test
    void insufficientFundsIsRefusedAndNothingMoves() {
        User ada = newUser("1000");
        User tunde = newUser("0");

        Resp r = internal(ada, tunde.account(), "5000", key());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(ada)).isEqualByComparingTo("1000.00");
        assertThat(balance(tunde)).isEqualByComparingTo("0.00");
    }

    @Test
    void youCannotSendFromSomeoneElsesAccount() {
        User ada = newUser("50000");
        User tunde = newUser("0");

        Resp r = post("/api/v1/transfers/internal", tunde.token(), Map.of("Idempotency-Key", key()),
                Map.of("sourceAccountNumber", ada.account(), "destinationAccountNumber", tunde.account(),
                        "amount", new BigDecimal("1000"), "pin", PIN));

        assertThat(r.status()).isEqualTo(404);
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
        assertThat(get("/api/v1/accounts/" + ada.account(), tunde.token()).status()).isEqualTo(404);
    }

    @Test
    void endpointsNeedAToken() {
        assertThat(get("/api/v1/accounts", null).status()).isEqualTo(401);
        assertThat(get("/api/v1/notifications", null).status()).isEqualTo(401);
    }

    @Test
    void wrongPinIsRefusedAndThreeWrongTriesLockThePin() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        Map<String, Object> bad = Map.of("sourceAccountNumber", ada.account(), "destinationAccountNumber",
                tunde.account(), "amount", new BigDecimal("1000"), "pin", "9999");

        assertThat(post("/api/v1/transfers/internal", ada.token(), Map.of("Idempotency-Key", key()), bad).code())
                .isEqualTo("PIN_INVALID");
        assertThat(post("/api/v1/transfers/internal", ada.token(), Map.of("Idempotency-Key", key()), bad).code())
                .isEqualTo("PIN_INVALID");
        assertThat(post("/api/v1/transfers/internal", ada.token(), Map.of("Idempotency-Key", key()), bad).code())
                .isEqualTo("PIN_INVALID");
        // now locked, even with the right PIN
        assertThat(internal(ada, tunde.account(), "1000", key()).code()).isEqualTo("PIN_LOCKED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aFrozenAccountCannotSendButCanStillReceive() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        assertThat(post("/api/v1/accounts/" + ada.account() + "/freeze", ada.token(), null, null).status()).isEqualTo(200);

        assertThat(internal(ada, tunde.account(), "1000", key()).code()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(internal(tunde, ada.account(), "1", key()).code()).isEqualTo("INSUFFICIENT_FUNDS");   // tunde has nothing
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void manyParallelTransfersNeverOverdrawAndNeverLoseMoney() throws Exception {
        User ada = newUser("10000");
        User tunde = newUser("0");
        int threads = 20;                       // 20 attempts of N1,000 against N10,000: exactly 10 can succeed
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Resp> job = () -> internal(ada, tunde.account(), "1000", key());
            futures.add(pool.submit(job));
        }
        int ok = 0;
        for (Future<Resp> f : futures) {
            if (f.get().status() == 201) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(10);
        assertThat(balance(ada)).isEqualByComparingTo("0.00");           // never below zero
        assertThat(balance(tunde)).isEqualByComparingTo("10000.00");     // conservation of money
    }

    @Test
    void theSameKeySentManyTimesAtOnceCreatesOneTransfer() throws Exception {
        User ada = newUser("50000");
        User tunde = newUser("0");
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> internal(ada, tunde.account(), "5000", key)));
        }
        for (Future<Resp> f : futures) {
            assertThat(f.get().status()).isIn(200, 201);
        }
        pool.shutdown();

        assertThat(balance(ada)).isEqualByComparingTo("45000.00");       // debited once
        Integer rows = jdbc.queryForObject(
                "select count(*) from transfers where source_account_number = ? and idempotency_key = ?",
                Integer.class, ada.account(), key);
        assertThat(rows).isEqualTo(1);
    }

    // ------------------------------------------------------------------ external payouts (fake gateway)

    @Test
    void externalPayoutDebitsFirstThenSettlesToSuccessful() {
        User ada = newUser("50000");
        long feeBefore = systemBalanceKobo("9000000001");

        Resp r = external(ada, "033", "0123456789", "20000", key());

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("status")).isEqualTo("PENDING");
        assertThat(r.body().get("destinationBankName").toString()).contains("UBA");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");       // 20,000 + fee 10 + VAT 0.75 + stamp 50

        Resp verified = post("/api/v1/transfers/" + r.body().get("reference") + "/verify", ada.token(), null, null);
        assertThat(verified.body().get("status")).isEqualTo("SUCCESSFUL");
        assertThat(systemBalanceKobo("9000000001") - feeBefore).isEqualTo(1000L);   // the fee is collected on success
    }

    @Test
    void externalPayoutThatFailsIsRefundedInFullExactlyOnce() {
        User ada = newUser("50000");

        Resp r = external(ada, "214", "9912345678", "20000", key());     // 99... fails at the receiving bank
        assertThat(r.status()).isEqualTo(202);
        String reference = (String) r.body().get("reference");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");

        Resp first = post("/api/v1/transfers/" + reference + "/verify", ada.token(), null, null);
        Resp second = post("/api/v1/transfers/" + reference + "/verify", ada.token(), null, null);

        assertThat(first.body().get("status")).isEqualTo("FAILED");
        assertThat(second.body().get("status")).isEqualTo("FAILED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");       // everything back, once
    }

    @Test
    void aProviderRejectionRefundsImmediately() {
        User ada = newUser("50000");

        Resp r = external(ada, "058", "7712345678", "20000", key());     // 77... rejected straight away

        assertThat(r.body().get("status")).isEqualTo("FAILED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aTimeoutIsNotRefundedAndIsSettledLaterByVerify() {
        User ada = newUser("50000");

        Resp r = external(ada, "044", "8812345678", "20000", key());     // 88... times out but the provider did pay

        assertThat(r.body().get("status")).isEqualTo("PENDING");         // NOT failed, NOT refunded
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");
        Resp verified = post("/api/v1/transfers/" + r.body().get("reference") + "/verify", ada.token(), null, null);
        assertThat(verified.body().get("status")).isEqualTo("SUCCESSFUL");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");       // and never paid twice
    }

    @Test
    void unknownBankAndUnresolvableAccountCostNothing() {
        User ada = newUser("50000");

        assertThat(external(ada, "999", "0123456789", "1000", key()).code()).isEqualTo("UNKNOWN_BANK");
        assertThat(external(ada, "033", "0012345678", "1000", key()).code()).isEqualTo("ACCOUNT_NOT_RESOLVED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    // ------------------------------------------------------------------ webhook

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void webhookNeedsAValidSignatureAndThenSettlesThePayout() throws Exception {
        User ada = newUser("50000");
        Resp r = external(ada, "033", "0123456789", "20000", key());
        String reference = (String) r.body().get("reference");
        String body = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"" + reference + "\"}}";

        Resp forged = post("/api/v1/webhooks/paystack", null, Map.of("x-paystack-signature", "deadbeef"), body);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(get("/api/v1/transfers/" + reference, ada.token()).body().get("status")).isEqualTo("PENDING");

        Resp genuine = post("/api/v1/webhooks/paystack", null,
                Map.of("x-paystack-signature", sign("test-paystack-secret", body)), body);
        assertThat(genuine.status()).isEqualTo(200);
        assertThat(get("/api/v1/transfers/" + reference, ada.token()).body().get("status")).isEqualTo("SUCCESSFUL");
    }

    // ------------------------------------------------------------------ audit and notifications

    @Test
    void auditLogCannotBeEditedOrDeleted() {
        User ada = newUser("1000");
        assertThat(ada.account()).isNotBlank();
        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'HACKED'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log")).hasMessageContaining("append-only");
    }

    @Test
    void receiverIsNotifiedAndCanReadIt() throws Exception {
        User ada = newUser("50000");
        User tunde = newUser("0");
        assertThat(internal(ada, tunde.account(), "5000", key()).status()).isEqualTo(201);

        long unread = 0;
        for (int i = 0; i < 40 && unread == 0; i++) {              // notifications are created a moment after the commit
            Thread.sleep(100);
            unread = ((Number) get("/api/v1/notifications/unread-count", tunde.token()).body().get("unread")).longValue();
        }
        assertThat(unread).isGreaterThanOrEqualTo(1);
    }

    @Test
    void quoteShowsTheChargesWithoutChangingAnything() {
        User ada = newUser("1000");
        Resp q = post("/api/v1/transfers/quote", ada.token(), null,
                Map.of("type", "EXTERNAL", "amount", new BigDecimal("20000")));

        assertThat(q.status()).isEqualTo(200);
        assertThat(new BigDecimal(String.valueOf(q.body().get("totalDebit")))).isEqualByComparingTo("20060.75");
        assertThat(balance(ada)).isEqualByComparingTo("1000.00");
    }
}
