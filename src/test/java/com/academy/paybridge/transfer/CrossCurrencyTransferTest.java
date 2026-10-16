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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step A5 (R-XFR-5 to 10, R-NTF-2, acceptance 2, 8, 9, 10). Real HTTP, real database, fixed test rates. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrossCurrencyTransferTest {

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
        String email = "xc-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test " + email.substring(3, 9), "email", email, "password", "password123"))
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
        assertThat(call(HttpMethod.POST, "/api/v1/dev/accounts/" + u.account() + "/fund", u.token(),
                Map.of("amount", new BigDecimal(amount), "currency", currency)).status()).isEqualTo(200);
    }

    void swapIn(User u, String currency, String park) {
        assertThat(call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", currency, "parkCurrency", park)).status()).isEqualTo(200);
    }

    BigDecimal balance(User u, String currency) {
        Map<String, Object> w = call(HttpMethod.GET, "/api/v1/wallets", u.token(), null).list().stream()
                .filter(x -> x.get("accountNumber").equals(u.account()) && x.get("currency").equals(currency))
                .findFirst().orElseThrow();
        return new BigDecimal(String.valueOf(w.get("balance")));
    }

    Resp quote(User from, User to, String source, String receive, String amount) {
        return call(HttpMethod.POST, "/api/v1/transfers/cross-currency/quotes", from.token(),
                Map.of("destinationAccountNumber", to.account(), "sourceCurrency", source,
                        "receiveCurrency", receive, "amount", new BigDecimal(amount)));
    }

    Resp confirm(User from, Object quoteId, String pin, String key) {
        return call(HttpMethod.POST, "/api/v1/transfers/cross-currency", from.token(),
                Map.of("Idempotency-Key", key), Map.of("quoteId", String.valueOf(quoteId), "pin", pin));
    }

    Resp sameCurrency(User from, User to, String currency, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceAccountNumber", from.account());
        body.put("destinationAccountNumber", to.account());
        body.put("amount", new BigDecimal(amount));
        body.put("currency", currency);
        body.put("pin", PIN);
        return call(HttpMethod.POST, "/api/v1/transfers/internal", from.token(),
                Map.of("Idempotency-Key", key()), body);
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

    @Test
    void aQuoteShowsRateFeeAndTheRecipientAndMovesNothing() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");

        Resp q = quote(a, b, "USD", "NGN", "50");

        assertThat(q.status()).isEqualTo(200);
        assertThat(q.map()).containsEntry("sourceCurrency", "USD").containsEntry("receiveCurrency", "NGN")
                .containsEntry("status", "ACTIVE").containsEntry("feeReason", "PROMO");   // new sign-up: first 30 days free
        assertThat(dec(q.map().get("fee"))).isEqualByComparingTo("0");
        assertThat(dec(q.map().get("receiveAmount"))).isPositive();
        assertThat(dec(q.map().get("rate"))).isPositive();
        assertThat(q.map().get("recipientName")).isNotNull();
        assertThat(balance(a, "USD")).isEqualByComparingTo("100");
        assertThat(balance(b, "NGN")).isEqualByComparingTo("0");
    }

    @Test
    void confirmingMovesBothWalletsExactlyAndBothSeeItInHistory() throws InterruptedException {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        Resp q = quote(a, b, "USD", "NGN", "50");
        BigDecimal receive = dec(q.map().get("receiveAmount"));

        Resp done = confirm(a, q.map().get("id"), PIN, key());

        assertThat(done.status()).isEqualTo(201);
        assertThat(done.map()).containsEntry("status", "SUCCESSFUL").containsEntry("currency", "USD")
                .containsEntry("targetCurrency", "NGN");
        assertThat(dec(done.map().get("totalDebit"))).isEqualByComparingTo("50");
        assertThat(dec(done.map().get("targetAmount"))).isEqualByComparingTo(receive);
        assertThat(balance(a, "USD")).isEqualByComparingTo("50");
        assertThat(balance(b, "NGN")).isEqualByComparingTo(receive);
        for (User u : List.of(a, b)) {
            Resp history = call(HttpMethod.GET, "/api/v1/accounts/" + u.account() + "/transfers", u.token(), null);
            assertThat(history.list()).hasSize(1);
            assertThat(history.list().get(0)).containsEntry("currency", "USD").containsEntry("targetCurrency", "NGN");
        }
        assertThat(notified(a, "You sent $50.00")).isTrue();
        assertThat(notified(b, "You received")).isTrue();
        assertBooksBalance();
    }

    @Test
    void aRecipientWhoCannotReceiveTheCurrencyIsRefusedWithTheAcceptedList() {
        User a = newUser();
        User b = newUser();
        swapIn(a, "JPY", "EUR");
        fund(a, "USD", "100");

        Resp q = quote(a, b, "USD", "JPY", "10");

        assertThat(q.status()).isEqualTo(422);
        assertThat(q.code()).isEqualTo("RECIPIENT_CANNOT_RECEIVE");
        assertThat(q.message()).contains("JPY").contains("EUR", "GBP", "NGN", "USD");
    }

    @Test
    void sameCurrencyAndYourOwnAccountAreRefused() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");

        assertThat(quote(a, b, "USD", "USD", "10").code()).isEqualTo("INVALID_CURRENCY");
        assertThat(quote(a, a, "USD", "NGN", "10").code()).isEqualTo("SAME_ACCOUNT");
        assertThat(quote(a, b, "USD", "ZZZ", "10").code()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void aQuoteIsSingleUseAndAWrongPinDoesNotBurnIt() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        Resp q = quote(a, b, "USD", "NGN", "30");

        Resp wrongPin = confirm(a, q.map().get("id"), "0000", key());
        Resp first = confirm(a, q.map().get("id"), PIN, key());
        Resp second = confirm(a, q.map().get("id"), PIN, key());

        assertThat(wrongPin.status()).isEqualTo(403);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.code()).isEqualTo("QUOTE_ALREADY_USED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("70");           // charged once
    }

    @Test
    void theSameKeyAndQuoteReplaysTheFirstAnswerAndAnotherQuoteWithThatKeyIsRefused() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        Resp q1 = quote(a, b, "USD", "NGN", "10");
        Resp q2 = quote(a, b, "USD", "NGN", "10");
        String key = key();

        Resp first = confirm(a, q1.map().get("id"), PIN, key);
        Resp again = confirm(a, q1.map().get("id"), PIN, key);
        Resp other = confirm(a, q2.map().get("id"), PIN, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.map().get("reference")).isEqualTo(first.map().get("reference"));
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("90");
    }

    @Test
    void anotherCustomersQuoteLooksLikeItDoesNotExist() {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        fund(a, "USD", "100");
        Resp q = quote(a, b, "USD", "NGN", "10");

        Resp stolen = confirm(c, q.map().get("id"), PIN, key());

        assertThat(stolen.status()).isEqualTo(404);
        assertThat(stolen.code()).isEqualTo("QUOTE_NOT_FOUND");
    }

    @Test
    void twoSimultaneousConfirmsOfOneQuoteTransferOnce() throws Exception {      // acceptance 8
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        Resp q = quote(a, b, "USD", "NGN", "40");
        Object id = q.map().get("id");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Resp>> results = pool.invokeAll(List.<Callable<Resp>>of(
                    () -> confirm(a, id, PIN, key()),
                    () -> confirm(a, id, PIN, key())), 60, TimeUnit.SECONDS);

            List<Integer> statuses = new ArrayList<>();
            for (Future<Resp> f : results) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(a, "USD")).isEqualByComparingTo("60");
        assertBooksBalance();
    }

    @Test
    void aConfirmedTransferThatFailsLeavesBothBalancesAndSaysNoMoneyMoved() throws InterruptedException {   // acceptance 9
        User a = newUser();
        User b = newUser();
        User c = newUser();
        fund(a, "USD", "100");
        Resp q = quote(a, b, "USD", "NGN", "80");
        assertThat(sameCurrency(a, c, "USD", "50").status()).isEqualTo(201);   // now only 50 is left; the quote needs 80

        Resp failed = confirm(a, q.map().get("id"), PIN, key());
        Resp retry = confirm(a, q.map().get("id"), PIN, key());

        assertThat(failed.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(retry.code()).isEqualTo("QUOTE_FAILED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("50");
        assertThat(balance(b, "NGN")).isEqualByComparingTo("0");
        assertThat(notified(a, "did not go through")).isTrue();
        assertThat(notified(a, "No money moved")).isTrue();
        assertBooksBalance();
    }

    @Test
    void aRefusalBeforeConfirmingSendsNoNotification() throws InterruptedException {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "10");

        Resp tooMuch = quote(a, b, "USD", "NGN", "20");

        assertThat(tooMuch.code()).isEqualTo("INSUFFICIENT_FUNDS");
        Thread.sleep(1000);
        assertThat(call(HttpMethod.GET, "/api/v1/notifications", a.token(), null).list()).isEmpty();
    }

    @Test
    void theDollarLimitsApplyAndTransfersShareOneDailyTotal() {                // R-LIM2, acceptance 7 and 21d
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "6000");

        assertThat(quote(a, b, "USD", "NGN", "1000.01").code()).isEqualTo("LIMIT_EXCEEDED");   // single limit

        for (int i = 0; i < 4; i++) {
            assertThat(sameCurrency(a, b, "USD", "1000").status()).isEqualTo(201);              // $4,000 used
        }
        Resp fifth = quote(a, b, "USD", "NGN", "1000");
        assertThat(fifth.status()).isEqualTo(200);
        assertThat(confirm(a, fifth.map().get("id"), PIN, key()).status()).isEqualTo(201);      // $5,000 used

        Resp sixth = quote(a, b, "USD", "NGN", "1");
        assertThat(sixth.status()).isEqualTo(422);
        assertThat(sixth.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(sixth.message()).contains("daily limit");
    }

    @Test
    void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {       // acceptance 10 (concurrency)
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "900");
        fund(b, "NGN", "9000000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                Resp qa = quote(a, b, "USD", "NGN", "20");
                Resp qb = quote(b, a, "NGN", "USD", "20000");
                assertThat(qa.status()).isEqualTo(200);
                assertThat(qb.status()).isEqualTo(200);

                List<Future<Resp>> both = pool.invokeAll(List.<Callable<Resp>>of(
                        () -> confirm(a, qa.map().get("id"), PIN, key()),
                        () -> confirm(b, qb.map().get("id"), PIN, key())), 60, TimeUnit.SECONDS);

                for (Future<Resp> f : both) {
                    assertThat(f.isCancelled()).isFalse();                       // a deadlock would hit the timeout
                    assertThat(f.get().status()).isEqualTo(201);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertBooksBalance();
    }
}