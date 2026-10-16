package com.academy.paybridge.settlement;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.JournalLine;
import com.academy.paybridge.ledger.api.JournalLine.Side;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step C4 (R-SET-11, R-SET-13; acceptance 27). Fees are seeded straight into the ledger. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "paybridge.settlement.admin-key=test-settlement-key",
        "paybridge.reconciliation.admin-key=test-recon-key"})
@ActiveProfiles("test")
class SettlementSweepsTest {

    private static final String KEY = "test-settlement-key";
    private static final String SWEEPS = "/api/v1/admin/settlement/sweeps";
    private static final String POSITIONS = "/api/v1/admin/settlement/positions";
    private static final String FEE = "9000000001:NGN";
    private static final String VAT = "9000000002:NGN";
    private static final String STAMP = "9000000003:NGN";

    @Value("${local.server.port}")
    int port;

    @Autowired
    LedgerApi ledger;
    @Autowired
    AccountApi accounts;
    @Autowired
    PlatformTransactionManager txManager;

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

    /** Any signed-in customer; the operator key is what makes a call an operator call. */
    String token() {
        String email = "sw-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null, null,
                Map.of("fullName", "Test " + email.substring(3, 9), "email", email, "password", "password123"))
                .status()).isEqualTo(201);
        return (String) call(HttpMethod.POST, "/api/v1/auth/login", null, null,
                Map.of("email", email, "password", "password123")).map().get("token");
    }

    /** Puts money in a system wallet: the wallet and the ledger move together, like a real charge would. */
    void seedWallet(String walletKey, long minor) {
        String number = LedgerApi.accountNumberOf(walletKey);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            accounts.credit(number, "NGN", minor);
            ledger.recordJournal("TEST-SEED:" + UUID.randomUUID(), null, "FUNDING", List.of(
                    new JournalLine("EXT:FUNDING", Side.DEBIT, "NGN", minor),
                    new JournalLine(walletKey, Side.CREDIT, "NGN", minor)));
        });
    }

    /** Puts money in a ledger-only account such as EXT:FX_FEE:USD. */
    void seedExternal(String account, String currency, long minor) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                ledger.recordJournal("TEST-SEED:" + UUID.randomUUID(), null, "FUNDING", List.of(
                        new JournalLine("EXT:FUNDING", Side.DEBIT, currency, minor),
                        new JournalLine(account, Side.CREDIT, currency, minor))));
    }

    Resp positions(String token) {
        return call(HttpMethod.GET, POSITIONS, token, Map.of("X-Admin-Key", KEY), null);
    }

    @SuppressWarnings("unchecked")
    long net(Resp r, String currency, String account) {
        for (Map<String, Object> c : (List<Map<String, Object>>) r.map().get("currencies")) {
            if (c.get("currency").equals(currency)) {
                for (Map<String, Object> p : (List<Map<String, Object>>) c.get("positions")) {
                    if (p.get("account").equals(account)) {
                        return ((Number) p.get("netMinor")).longValue();
                    }
                }
            }
        }
        return 0L;
    }

    Resp sweep(String token, Object body) {
        return call(HttpMethod.POST, SWEEPS, token, Map.of("X-Admin-Key", KEY), body);
    }

    /** Total swept from one source in one sweep response. */
    @SuppressWarnings("unchecked")
    long swept(Resp r, String source) {
        long total = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) r.map().get("swept")) {
            if (s.get("source").equals(source)) {
                total += ((Number) s.get("amountMinor")).longValue();
            }
        }
        return total;
    }

    void assertReconciliationClean(String token) {
        Resp r = call(HttpMethod.GET, "/api/v1/admin/reconciliation", token, Map.of("X-Admin-Key", "test-recon-key"), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.map().get("ok")).as(String.valueOf(r.map().get("problems"))).isEqualTo(true);
    }

    @Test
    void sweepingBringsTheFeeAccountsToZeroAndMovesTheSameToRevenueAndTaxPayable() {   // acceptance 27
        String token = token();
        seedWallet(FEE, 7_000);
        seedWallet(VAT, 525);
        seedWallet(STAMP, 100);
        seedExternal("EXT:FX_FEE:USD", "USD", 300);
        seedExternal("EXT:PAYOUT_FEE:USD", "USD", 100);
        Resp before = positions(token);

        Resp result = sweep(token, null);
        Resp after = positions(token);

        assertThat(result.status()).isEqualTo(200);
        assertThat(net(after, "NGN", FEE)).isZero();
        assertThat(net(after, "NGN", VAT)).isZero();
        assertThat(net(after, "NGN", STAMP)).isZero();
        assertThat(net(after, "USD", "EXT:FX_FEE:USD")).isZero();
        assertThat(net(after, "USD", "EXT:PAYOUT_FEE:USD")).isZero();

        long revenueNgn = net(before, "NGN", FEE) + net(before, "NGN", "EXT:FX_FEE:NGN")
                + net(before, "NGN", "EXT:PAYOUT_FEE:NGN");
        assertThat(net(after, "NGN", "EXT:REVENUE:NGN") - net(before, "NGN", "EXT:REVENUE:NGN")).isEqualTo(revenueNgn);
        assertThat(net(after, "NGN", "EXT:TAX_PAYABLE:NGN") - net(before, "NGN", "EXT:TAX_PAYABLE:NGN"))
                .isEqualTo(net(before, "NGN", VAT) + net(before, "NGN", STAMP));
        assertThat(net(after, "USD", "EXT:REVENUE:USD") - net(before, "USD", "EXT:REVENUE:USD"))
                .isEqualTo(net(before, "USD", "EXT:FX_FEE:USD") + net(before, "USD", "EXT:PAYOUT_FEE:USD"));
        assertThat((List<?>) result.map().get("failed")).isEmpty();
        assertReconciliationClean(token);                                         // R-SET-13
    }

    @Test
    void aSecondSweepFindsNothingLeftToMove() {
        String token = token();
        seedWallet(FEE, 1_000);
        sweep(token, null);

        Resp again = sweep(token, null);

        assertThat(again.status()).isEqualTo(200);
        assertThat((List<?>) again.map().get("swept")).isEmpty();
        assertThat((List<?>) again.map().get("nothingToSweep"))
                .anySatisfy(item -> assertThat(item).isEqualTo(FEE));
        assertReconciliationClean(token);
    }

    @Test
    void twoSweepsAtTheSameTimeNeverMoveTheSameMoneyTwice() throws Exception {
        String token = token();
        seedWallet(FEE, 5_000);
        Resp before = positions(token);
        long expected = net(before, "NGN", FEE);
        Map<String, String> body = Map.of("currency", "NGN");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Resp> job = () -> sweep(token, body);
            Future<Resp> a = pool.submit(job);
            Future<Resp> b = pool.submit(job);
            Resp ra = a.get();
            Resp rb = b.get();

            assertThat(ra.status()).isEqualTo(200);
            assertThat(rb.status()).isEqualTo(200);
            assertThat(swept(ra, FEE) + swept(rb, FEE)).isEqualTo(expected);
        } finally {
            pool.shutdown();
        }
        assertThat(net(positions(token), "NGN", FEE)).isZero();
        assertReconciliationClean(token);
    }

    @Test
    void sweepsNeedBothTheSignInAndTheOperatorKey() {                             // acceptance 28
        String token = token();

        assertThat(call(HttpMethod.POST, SWEEPS, null, Map.of("X-Admin-Key", KEY), null).status()).isEqualTo(401);
        Resp noKey = call(HttpMethod.POST, SWEEPS, token, null, null);
        assertThat(noKey.status()).isEqualTo(403);
        assertThat(noKey.code()).isEqualTo("ADMIN_KEY_REQUIRED");
        assertThat(call(HttpMethod.POST, SWEEPS, token, Map.of("X-Admin-Key", "not-the-key"), null).status())
                .isEqualTo(403);
    }

    @Test
    void aBadCurrencyCodeIsRefused() {
        assertThat(sweep(token(), Map.of("currency", "naira")).status()).isEqualTo(400);
    }
}