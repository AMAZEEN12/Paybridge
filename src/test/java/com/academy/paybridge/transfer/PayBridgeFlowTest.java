package com.academy.paybridge.transfer;

import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.audit.AuditLogRepository;
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
 * End-to-end tests against a real Postgres database, through the real HTTP API.
 *
 * Tests skip themselves if the paybridge_test database is unavailable.
 *
 * Create the database once with:
 * createdb paybridge_test
 *
 * Do not put @Transactional on this class because the rollback and lock
 * tests need real commits.
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

    @Autowired
    ReconciliationService reconciliation;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(
                TestDb.available(),
                "No test database. Create one named paybridge_test to run these tests."
        );
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    record Resp(
            int status,
            Map<String, Object> body,
            Map<String, String> headers
    ) {
        String code() {
            return body == null ? null : String.valueOf(body.get("code"));
        }
    }

    @SuppressWarnings("unchecked")
    Resp call(
            HttpMethod method,
            String path,
            String token,
            Map<String, String> headers,
            Object body
    ) {
        RestClient client = RestClient.create("http://localhost:" + port);

        RestClient.RequestBodySpec spec = client.method(method)
                .uri(path)
                .headers(h -> {
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
            Map<String, String> responseHeaders = new LinkedHashMap<>();

            response.getHeaders().forEach(
                    (key, values) -> responseHeaders.put(
                            key.toLowerCase(),
                            String.join(",", values)
                    )
            );

            Map<String, Object> parsedBody = null;

            try {
                parsedBody = response.bodyTo(Map.class);
            } catch (RuntimeException ignored) {
                // The response may have an empty body, for example HTTP 204.
            }

            return new Resp(
                    response.getStatusCode().value(),
                    parsedBody,
                    responseHeaders
            );
        });
    }

    Resp post(
            String path,
            String token,
            Map<String, String> headers,
            Object body
    ) {
        return call(HttpMethod.POST, path, token, headers, body);
    }

    Resp get(String path, String token) {
        return call(HttpMethod.GET, path, token, null, null);
    }

    record User(String token, String account) {
    }

    /**
     * Registers a customer, logs in, sets a PIN, opens or retrieves an
     * account, and funds it when the requested amount is positive.
     */
    User newUser(String fundNaira) {
        String email = "user-" + UUID.randomUUID() + "@example.com";

        Resp registered = post(
                "/api/v1/auth/register",
                null,
                null,
                Map.of(
                        "fullName", "Test User",
                        "email", email,
                        "password", "password123"
                )
        );

        assertThat(registered.status()).isEqualTo(201);

        Resp login = post(
                "/api/v1/auth/login",
                null,
                null,
                Map.of(
                        "email", email,
                        "password", "password123"
                )
        );

        assertThat(login.status()).isEqualTo(200);

        String token = (String) login.body().get("token");
        assertThat(token).isNotBlank();

        assertThat(
                post(
                        "/api/v1/customers/me/pin",
                        token,
                        null,
                        Map.of("pin", PIN)
                ).status()
        ).isEqualTo(204);

        // Registration may already have created the customer's account.
        Resp opened = post("/api/v1/accounts", token, null, null);

        assertThat(opened.status()).isEqualTo(200);
        assertThat(opened.body()).isNotNull();

        String account = (String) opened.body().get("accountNumber");
        assertThat(account).isNotBlank();

        if (new BigDecimal(fundNaira).signum() > 0) {
            Resp funded = post(
                    "/api/v1/dev/accounts/" + account + "/fund",
                    token,
                    null,
                    Map.of("amount", new BigDecimal(fundNaira))
            );

            assertThat(funded.status()).isEqualTo(200);
        }

        return new User(token, account);
    }

    BigDecimal balance(User user) {
        Resp response = get(
                "/api/v1/accounts/" + user.account(),
                user.token()
        );

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isNotNull();

        return new BigDecimal(
                String.valueOf(response.body().get("balance"))
        );
    }

    long systemBalanceKobo(String number) {
        return accountRepository.findByAccountNumber(number)
                .orElseThrow()
                .getBalanceKobo();
    }

    Resp internal(User from, String to, String amount, String key) {
        return post(
                "/api/v1/transfers/internal",
                from.token(),
                Map.of("Idempotency-Key", key),
                Map.of(
                        "sourceAccountNumber", from.account(),
                        "destinationAccountNumber", to,
                        "amount", new BigDecimal(amount),
                        "pin", PIN
                )
        );
    }

    Resp external(
            User from,
            String bankCode,
            String destinationAccount,
            String amount,
            String key
    ) {
        return post(
                "/api/v1/transfers/external",
                from.token(),
                Map.of("Idempotency-Key", key),
                Map.of(
                        "sourceAccountNumber", from.account(),
                        "bankCode", bankCode,
                        "destinationAccountNumber", destinationAccount,
                        "amount", new BigDecimal(amount),
                        "pin", PIN
                )
        );
    }

    String key() {
        return "key-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------
    // Internal transfers
    // ------------------------------------------------------------------

    @Test
    void internalTransferMovesMoneyAndCollectsStampDuty() {
        User ada = newUser("50000");
        User tunde = newUser("0");

        long stampBefore = systemBalanceKobo("9000000003");

        Resp response = internal(ada, tunde.account(), "20000", key());

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("status")).isEqualTo("SUCCESSFUL");
        assertThat(balance(ada)).isEqualByComparingTo("29950.00");
        assertThat(balance(tunde)).isEqualByComparingTo("20000.00");

        assertThat(systemBalanceKobo("9000000003") - stampBefore)
                .isEqualTo(5000L);
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
        assertThat(second.body().get("reference"))
                .isEqualTo(first.body().get("reference"));

        assertThat(balance(ada)).isEqualByComparingTo("45000.00");
    }

    @Test
    void sameKeyWithADifferentAmountIsRefused() {
        User ada = newUser("50000");
        User tunde = newUser("0");
        String key = key();

        assertThat(internal(ada, tunde.account(), "5000", key).status())
                .isEqualTo(201);

        Resp second = internal(ada, tunde.account(), "9000", key);

        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(ada)).isEqualByComparingTo("45000.00");
    }

    @Test
    void insufficientFundsIsRefusedAndNothingMoves() {
        User ada = newUser("1000");
        User tunde = newUser("0");

        Resp response = internal(ada, tunde.account(), "5000", key());

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(ada)).isEqualByComparingTo("1000.00");
        assertThat(balance(tunde)).isEqualByComparingTo("0.00");
    }

    @Test
    void youCannotSendFromSomeoneElsesAccount() {
        User ada = newUser("50000");
        User tunde = newUser("0");

        Resp response = post(
                "/api/v1/transfers/internal",
                tunde.token(),
                Map.of("Idempotency-Key", key()),
                Map.of(
                        "sourceAccountNumber", ada.account(),
                        "destinationAccountNumber", tunde.account(),
                        "amount", new BigDecimal("1000"),
                        "pin", PIN
                )
        );

        assertThat(response.status()).isEqualTo(404);
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");

        assertThat(
                get("/api/v1/accounts/" + ada.account(), tunde.token()).status()
        ).isEqualTo(404);
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

        Map<String, Object> bad = Map.of(
                "sourceAccountNumber", ada.account(),
                "destinationAccountNumber", tunde.account(),
                "amount", new BigDecimal("1000"),
                "pin", "9999"
        );

        assertThat(
                post("/api/v1/transfers/internal", ada.token(),
                        Map.of("Idempotency-Key", key()), bad).code()
        ).isEqualTo("PIN_INVALID");

        assertThat(
                post("/api/v1/transfers/internal", ada.token(),
                        Map.of("Idempotency-Key", key()), bad).code()
        ).isEqualTo("PIN_INVALID");

        assertThat(
                post("/api/v1/transfers/internal", ada.token(),
                        Map.of("Idempotency-Key", key()), bad).code()
        ).isEqualTo("PIN_INVALID");

        // The PIN should now be locked, even when the correct PIN is used.
        assertThat(internal(ada, tunde.account(), "1000", key()).code())
                .isEqualTo("PIN_LOCKED");

        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aFrozenAccountCannotSendButCanStillReceive() {
        User ada = newUser("50000");
        User tunde = newUser("0");

        assertThat(
                post("/api/v1/accounts/" + ada.account() + "/freeze",
                        ada.token(), null, null).status()
        ).isEqualTo(200);

        assertThat(internal(ada, tunde.account(), "1000", key()).code())
                .isEqualTo("ACCOUNT_FROZEN");

        assertThat(internal(tunde, ada.account(), "1", key()).code())
                .isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @Test
    void manyParallelTransfersNeverOverdrawAndNeverLoseMoney() throws Exception {
        User ada = newUser("10000");
        User tunde = newUser("0");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Resp>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                Callable<Resp> job =
                        () -> internal(ada, tunde.account(), "1000", key());

                futures.add(pool.submit(job));
            }

            int successful = 0;

            for (Future<Resp> future : futures) {
                if (future.get().status() == 201) {
                    successful++;
                }
            }

            assertThat(successful).isEqualTo(10);
            assertThat(balance(ada)).isEqualByComparingTo("0.00");
            assertThat(balance(tunde)).isEqualByComparingTo("10000.00");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameKeySentManyTimesAtOnceCreatesOneTransfer() throws Exception {
        User ada = newUser("50000");
        User tunde = newUser("0");
        String key = key();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Resp>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(
                        () -> internal(ada, tunde.account(), "5000", key)
                ));
            }

            for (Future<Resp> future : futures) {
                assertThat(future.get().status()).isIn(200, 201);
            }

            assertThat(balance(ada)).isEqualByComparingTo("45000.00");

            Integer rows = jdbc.queryForObject(
                    "select count(*) from transfers "
                            + "where source_account_number = ? "
                            + "and idempotency_key = ?",
                    Integer.class,
                    ada.account(),
                    key
            );

            assertThat(rows).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // External payouts (fake gateway)
    // ------------------------------------------------------------------

    @Test
    void externalPayoutDebitsFirstThenSettlesToSuccessful() {
        User ada = newUser("50000");
        long feeBefore = systemBalanceKobo("9000000001");

        Resp response = external(
                ada, "033", "0123456789", "20000", key()
        );

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body().get("status")).isEqualTo("PENDING");
        assertThat(response.body().get("destinationBankName").toString())
                .contains("UBA");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");

        Resp verified = post(
                "/api/v1/transfers/" + response.body().get("reference")
                        + "/verify",
                ada.token(),
                null,
                null
        );

        assertThat(verified.body().get("status")).isEqualTo("SUCCESSFUL");

        assertThat(systemBalanceKobo("9000000001") - feeBefore)
                .isEqualTo(1000L);
    }

    @Test
    void externalPayoutThatFailsIsRefundedInFullExactlyOnce() {
        User ada = newUser("50000");

        Resp response = external(
                ada, "214", "9912345678", "20000", key()
        );

        assertThat(response.status()).isEqualTo(202);

        String reference = (String) response.body().get("reference");

        assertThat(balance(ada)).isEqualByComparingTo("29939.25");

        Resp first = post(
                "/api/v1/transfers/" + reference + "/verify",
                ada.token(), null, null
        );

        Resp second = post(
                "/api/v1/transfers/" + reference + "/verify",
                ada.token(), null, null
        );

        assertThat(first.body().get("status")).isEqualTo("FAILED");
        assertThat(second.body().get("status")).isEqualTo("FAILED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aProviderRejectionRefundsImmediately() {
        User ada = newUser("50000");

        Resp response = external(
                ada, "058", "7712345678", "20000", key()
        );

        assertThat(response.body().get("status")).isEqualTo("FAILED");
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aTimeoutIsNotRefundedAndIsSettledLaterByVerify() {
        User ada = newUser("50000");

        Resp response = external(
                ada, "044", "8812345678", "20000", key()
        );

        assertThat(response.body().get("status")).isEqualTo("PENDING");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");

        Resp verified = post(
                "/api/v1/transfers/" + response.body().get("reference")
                        + "/verify",
                ada.token(), null, null
        );

        assertThat(verified.body().get("status")).isEqualTo("SUCCESSFUL");
        assertThat(balance(ada)).isEqualByComparingTo("29939.25");
    }

    @Test
    void unknownBankAndUnresolvableAccountCostNothing() {
        User ada = newUser("50000");

        assertThat(
                external(ada, "999", "0123456789", "1000", key()).code()
        ).isEqualTo("UNKNOWN_BANK");

        assertThat(
                external(ada, "033", "0012345678", "1000", key()).code()
        ).isEqualTo("ACCOUNT_NOT_RESOLVED");

        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    // ------------------------------------------------------------------
    // Webhook
    // ------------------------------------------------------------------

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");

        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA512"
        ));

        return HexFormat.of().formatHex(
                mac.doFinal(body.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void webhookNeedsAValidSignatureAndThenSettlesThePayout() throws Exception {
        User ada = newUser("50000");

        Resp response = external(
                ada, "033", "0123456789", "20000", key()
        );

        String reference = (String) response.body().get("reference");

        String body = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\""
                + reference + "\"}}";

        Resp forged = post(
                "/api/v1/webhooks/paystack",
                null,
                Map.of("x-paystack-signature", "deadbeef"),
                body
        );

        assertThat(forged.status()).isEqualTo(401);

        assertThat(
                get("/api/v1/transfers/" + reference, ada.token())
                        .body().get("status")
        ).isEqualTo("PENDING");

        Resp genuine = post(
                "/api/v1/webhooks/paystack",
                null,
                Map.of(
                        "x-paystack-signature",
                        sign("test-paystack-secret", body)
                ),
                body
        );

        assertThat(genuine.status()).isEqualTo(200);

        assertThat(
                get("/api/v1/transfers/" + reference, ada.token())
                        .body().get("status")
        ).isEqualTo("SUCCESSFUL");
    }

    // ------------------------------------------------------------------
    // Audit and notifications
    // ------------------------------------------------------------------

    @Test
    void auditLogCannotBeEditedOrDeleted() {
        User ada = newUser("1000");

        assertThat(ada.account()).isNotBlank();

        assertThatThrownBy(
                () -> jdbc.update("update audit_log set action = 'HACKED'")
        ).hasMessageContaining("append-only");

        assertThatThrownBy(
                () -> jdbc.update("delete from audit_log")
        ).hasMessageContaining("append-only");
    }

    @Test
    void receiverIsNotifiedAndCanReadIt() throws Exception {
        User ada = newUser("50000");
        User tunde = newUser("0");

        assertThat(
                internal(ada, tunde.account(), "5000", key()).status()
        ).isEqualTo(201);

        long unread = 0;

        for (int i = 0; i < 40 && unread == 0; i++) {
            Thread.sleep(100);

            unread = ((Number) get(
                    "/api/v1/notifications/unread-count",
                    tunde.token()
            ).body().get("unread")).longValue();
        }

        assertThat(unread).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theLedgerStaysBalancedAfterEveryKindOfTransfer() {
        User ada = newUser("100000");
        User tunde = newUser("0");

        assertThat(
                internal(ada, tunde.account(), "5000", key()).status()
        ).isEqualTo(201);

        Resp successful = external(
                ada, "033", "0123456789", "20000", key()
        );

        post(
                "/api/v1/transfers/" + successful.body().get("reference")
                        + "/verify",
                ada.token(), null, null
        );

        Resp failed = external(
                ada, "214", "9912345678", "10000", key()
        );

        post(
                "/api/v1/transfers/" + failed.body().get("reference")
                        + "/verify",
                ada.token(), null, null
        );

        external(ada, "058", "7712345678", "3000", key());
        external(ada, "044", "8812345678", "4000", key());

        ReconciliationReport report = reconciliation.run();

        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.inFlightKobo()).isGreaterThan(0L);

        assertThat(
                report.heldKobo()
                        + report.inFlightKobo()
                        + report.paidOutKobo()
        ).isEqualTo(report.fundedKobo());
    }

    @Test
    void quoteShowsTheChargesWithoutChangingAnything() {
        User ada = newUser("1000");

        Resp quote = post(
                "/api/v1/transfers/quote",
                ada.token(),
                null,
                Map.of(
                        "type", "EXTERNAL",
                        "amount", new BigDecimal("20000")
                )
        );

        assertThat(quote.status()).isEqualTo(200);

        assertThat(
                new BigDecimal(
                        String.valueOf(quote.body().get("totalDebit"))
                )
        ).isEqualByComparingTo("20060.75");

        assertThat(balance(ada)).isEqualByComparingTo("1000.00");
    }
}
