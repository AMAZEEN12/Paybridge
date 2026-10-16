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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plan 002-004, step A3 (R-XFR-1 to 4, R-NTF-1, acceptance 1 and 6). Real HTTP, real database, real ledger. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SameCurrencyTransferTest {

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
        String email = "xfer-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("fullName", "Test " + email.substring(5, 11), "email", email, "password", "password123"))
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

    /** Activates a currency by parking another one (four wallets are active by default). */
    void swapIn(User u, String currency, String park) {
        assertThat(call(HttpMethod.POST, "/api/v1/wallets/swap", u.token(),
                Map.of("currency", currency, "parkCurrency", park)).status()).isEqualTo(200);
    }

    Map<String, Object> wallet(User u, String currency) {
        return call(HttpMethod.GET, "/api/v1/wallets", u.token(), null).list().stream()
                .filter(w -> w.get("accountNumber").equals(u.account()) && w.get("currency").equals(currency))
                .findFirst().orElseThrow();
    }

    BigDecimal balance(User u, String currency) {
        return new BigDecimal(String.valueOf(wallet(u, currency).get("balance")));
    }

    Resp send(User from, User to, String currency, String amount, String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceAccountNumber", from.account());
        body.put("destinationAccountNumber", to.account());
        body.put("amount", new BigDecimal(amount));
        body.put("pin", PIN);
        if (currency != null) {
            body.put("currency", currency);
        }
        return call(HttpMethod.POST, "/api/v1/transfers/internal", from.token(), Map.of("Idempotency-Key", key), body);
    }

    static String key() {
        return "k-" + UUID.randomUUID();
    }

    static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /** Notifications are written a moment after the transfer commits, so wait for them. */
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
    void aUsdTransferMovesExactlyTheAmountWithNoCharges() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "500");

        Resp r = send(a, b, "USD", "100", key());

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.map()).containsEntry("currency", "USD").containsEntry("status", "SUCCESSFUL");
        assertThat(dec(r.map().get("amount"))).isEqualByComparingTo("100");
        assertThat(dec(r.map().get("fee"))).isEqualByComparingTo("0");
        assertThat(dec(r.map().get("stampDuty"))).isEqualByComparingTo("0");
        assertThat(dec(r.map().get("totalDebit"))).isEqualByComparingTo("100");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertThat(balance(b, "USD")).isEqualByComparingTo("100");
        assertThat(balance(a, "NGN")).isEqualByComparingTo("0");           // no other wallet moved
        assertThat(balance(b, "NGN")).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void bothCustomersAreToldInThatCurrency() throws InterruptedException {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "500");

        assertThat(send(a, b, "USD", "100", key()).status()).isEqualTo(201);

        assertThat(notified(a, "You sent $100.00")).isTrue();
        assertThat(notified(b, "You received $100.00")).isTrue();
    }

    @Test
    void historyShowsTheCurrencyToBothSides() {
        User a = newUser();
        User b = newUser();
        fund(a, "EUR", "50");
        assertThat(send(a, b, "EUR", "20", key()).status()).isEqualTo(201);

        for (User u : List.of(a, b)) {
            Resp history = call(HttpMethod.GET, "/api/v1/accounts/" + u.account() + "/transfers", u.token(), null);
            assertThat(history.status()).isEqualTo(200);
            assertThat(history.list()).hasSize(1);
            assertThat(history.list().get(0)).containsEntry("currency", "EUR");
        }
    }

    @Test
    void onlyNairaPaysStampDuty() {
        User a = newUser();
        User b = newUser();
        fund(a, null, "50000");
        fund(a, "USD", "500");

        Resp naira = send(a, b, null, "10000", key());

//        System.out.println("NGN RESPONSE STATUS: " + naira.status());
//        System.out.println("NGN RESPONSE CODE: " + naira.code());
//        System.out.println("NGN RESPONSE MESSAGE: " + naira.message());
//        System.out.println("NGN RESPONSE BODY: " + naira.body());
        Resp dollars = send(a, b, "USD", "100", key());

        assertThat(naira.status())
                .as("NGN transfer response: " + naira.body())
                .isEqualTo(201);
        assertThat(naira.map()).containsEntry("currency", "NGN");
        assertThat(dec(naira.map().get("stampDuty"))).isEqualByComparingTo("50");
        assertThat(dec(naira.map().get("totalDebit"))).isEqualByComparingTo("10050");

        assertThat(dollars.status())
                .as("USD transfer response: " + dollars.body())
                .isEqualTo(201);
        assertThat(dollars.map()).containsEntry("currency", "USD");
        assertThat(dec(dollars.map().get("stampDuty")))
                .isEqualByComparingTo("0");
        assertThat(dec(dollars.map().get("totalDebit")))
                .isEqualByComparingTo("100");

        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
    }

    @Test
    void aRecipientWithoutThatWalletIsRefusedAndNothingMoves() {
        User a = newUser();
        User b = newUser();
        swapIn(a, "JPY", "EUR");
        fund(a, "JPY", "5000");

        Resp r = send(a, b, "JPY", "1000", key());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("RECIPIENT_CANNOT_RECEIVE");
        assertThat(r.message()).contains("JPY").contains("EUR", "GBP", "NGN", "USD");   // says what they do accept
        assertThat(balance(a, "JPY")).isEqualByComparingTo("5000");
    }

    @Test
    void aParkedRecipientWalletStillReceives() {
        User a = newUser();
        User b = newUser();
        swapIn(b, "JPY", "EUR");                                       // b's EUR wallet is now parked
        fund(a, "EUR", "50");

        Resp r = send(a, b, "EUR", "20", key());

        assertThat(r.status()).isEqualTo(201);
        assertThat(wallet(b, "EUR")).containsEntry("status", "PARKED");
        assertThat(balance(b, "EUR")).isEqualByComparingTo("20");
        assertBooksBalance();
    }

    @Test
    void aParkedSenderWalletCannotSend() {
        User a = newUser();
        User b = newUser();
        fund(a, "EUR", "50");
        swapIn(a, "JPY", "EUR");                                       // a's EUR wallet is now parked

        Resp r = send(a, b, "EUR", "20", key());

        assertThat(r.code()).isEqualTo("WALLET_PARKED");
        assertThat(balance(a, "EUR")).isEqualByComparingTo("50");
    }

    @Test
    void aSenderWithoutThatWalletIsRefused() {
        User a = newUser();
        User b = newUser();
        swapIn(b, "JPY", "EUR");                                       // b can receive JPY, a has no JPY wallet

        Resp r = send(a, b, "JPY", "10", key());

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void notEnoughMoneyInThatWalletIsRefused() {
        User a = newUser();
        User b = newUser();
        fund(a, "NGN", "100000");                                      // Naira cannot pay for a dollar transfer
        fund(a, "USD", "5");

        Resp r = send(a, b, "USD", "6", key());

        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(a, "USD")).isEqualByComparingTo("5");
        assertThat(balance(a, "NGN")).isEqualByComparingTo("100000");
    }

    @Test
    void anUnknownCurrencyIsRefused() {
        User a = newUser();
        User b = newUser();

        Resp r = send(a, b, "ZZZ", "10", key());

        assertThat(r.status()).isGreaterThanOrEqualTo(400).isLessThan(500);
        assertThat(r.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void amountsFollowTheCurrencysOwnDecimals() {
        User a = newUser();
        User b = newUser();
        swapIn(a, "JPY", "EUR");
        swapIn(b, "JPY", "EUR");
        fund(a, "JPY", "1000");

        Resp half = send(a, b, "JPY", "10.5", key());                  // yen has no decimals
        Resp whole = send(a, b, "JPY", "10", key());

        assertThat(half.status()).isEqualTo(400);
        assertThat(half.code()).isEqualTo("INVALID_AMOUNT");
        assertThat(whole.status()).isEqualTo(201);
        assertThat(dec(whole.map().get("amount"))).isEqualByComparingTo("10");
        assertThat(balance(a, "JPY")).isEqualByComparingTo("990");
        assertThat(balance(b, "JPY")).isEqualByComparingTo("10");
    }

    @Test
    void theSameKeyReplaysAndAnotherCurrencyWithThatKeyIsRefused() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        fund(a, "EUR", "100");
        String key = key();

        Resp first = send(a, b, "USD", "10", key);
        Resp again = send(a, b, "USD", "10", key);
        Resp otherCurrency = send(a, b, "EUR", "10", key);             // same amount, different currency

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.map().get("reference")).isEqualTo(first.map().get("reference"));
        assertThat(otherCurrency.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("90");      // charged once
        assertThat(balance(a, "EUR")).isEqualByComparingTo("100");
    }

    @Test
    void aFrozenSenderCannotSendInAnyCurrency() {
        User a = newUser();
        User b = newUser();
        fund(a, "USD", "100");
        assertThat(call(HttpMethod.POST, "/api/v1/accounts/" + a.account() + "/freeze", a.token(), null).status())
                .isEqualTo(200);

        Resp r = send(a, b, "USD", "10", key());

        assertThat(r.code()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(balance(a, "USD")).isEqualByComparingTo("100");
    }
}