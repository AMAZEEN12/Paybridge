package com.academy.paybridge.remittance.client;

import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlutterwavePayoutGatewayContractTest {

    private HttpServer server;
    private FlutterwavePayoutGateway gateway;
    private int status;
    private String responseBody;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        var fw = new PayoutProperties.Flutterwave("http://127.0.0.1:" + server.getAddress().getPort(),
                "FLWSECK_TEST-abc", "hash", 1000, 2000, "OWN_LOCK", "auto", 24, 0, List.of("NG"));
        var props = new PayoutProperties("flutterwave", fw, null, null, null, null);
        var registry = new CurrencyRegistry(List.of(
                new CurrencyInfo("NGN", "Naira", "N", 2, true),
                new CurrencyInfo("USD", "Dollar", "$", 2, true)), List.of());
        gateway = new FlutterwavePayoutGateway(props, registry);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int code, String body) {
        status = code;
        responseBody = body;
    }

    private PayoutRequest request() {
        return new PayoutRequest("PB-REF-1", "USD", "NGN", 150_000_00L, "NG", "044", "0690000031",
                "Ada Obi", null, 10_000L, "FAMILY_SUPPORT");
    }

    @Test
    void rateHasNoProviderRefSoItIsOwnLock() {
        answer(200, "{\"status\":\"success\",\"message\":\"Transfer amount fetched\","
                + "\"data\":{\"rate\":1500.5,\"source\":{\"currency\":\"USD\",\"amount\":1},"
                + "\"destination\":{\"currency\":\"NGN\",\"amount\":1500.5}}}");
        ProviderRate rate = gateway.getRate("USD", "NGN");
        assertThat(rate.rate()).isEqualByComparingTo("1500.5");
        assertThat(rate.providerRef()).isNull();
        assertThat(rate.expiresAt()).isNull();
        assertThat(lastAuth.get()).isEqualTo("Bearer FLWSECK_TEST-abc");
    }

    @Test
    void payoutSendsExactDestinationAmountAndOurReference() {
        answer(200, "{\"status\":\"success\",\"message\":\"Transfer Queued Successfully\","
                + "\"data\":{\"id\":190626,\"reference\":\"PB-REF-1\",\"status\":\"NEW\"}}");
        PayoutResult r = gateway.initiatePayout(request());
        assertThat(r.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(r.providerRef()).isEqualTo("190626");
        assertThat(lastBody.get()).contains("\"reference\":\"PB-REF-1\"")
                .contains("\"currency\":\"NGN\"").contains("\"debit_currency\":\"USD\"")
                .contains("\"amount\":150000.00");
    }

    @Test
    void rejectionIsKnownAndRefundable() {
        answer(400, "{\"status\":\"error\",\"message\":\"Invalid account number\",\"data\":null}");
        assertThatThrownBy(() -> gateway.initiatePayout(request())).isInstanceOf(PayoutRejectedException.class);
    }

    @Test
    void duplicateReferenceMeansAlreadyReceivedNotRejected() {
        answer(400, "{\"status\":\"error\",\"message\":\"Transfer with this reference already exists\",\"data\":null}");
        assertThat(gateway.initiatePayout(request()).status()).isEqualTo(PayoutStatus.PENDING);
    }

    @Test
    void serverErrorStaysUnknown() {
        answer(500, "{\"status\":\"error\",\"message\":\"boom\"}");
        assertThatThrownBy(() -> gateway.initiatePayout(request())).isInstanceOf(PayoutUnavailableException.class);
    }

    @Test
    void verifyMapsSuccessfulFailedAndPending() {
        answer(200, "{\"status\":\"success\",\"data\":[{\"id\":1,\"reference\":\"PB-REF-1\",\"status\":\"SUCCESSFUL\"}]}");
        assertThat(gateway.verifyPayout("PB-REF-1").status()).isEqualTo(PayoutStatus.SUCCESS);
        answer(200, "{\"status\":\"success\",\"data\":[{\"id\":1,\"reference\":\"PB-REF-1\",\"status\":\"FAILED\","
                + "\"complete_message\":\"Insufficient funds\"}]}");
        assertThat(gateway.verifyPayout("PB-REF-1").status()).isEqualTo(PayoutStatus.FAILED);
        answer(200, "{\"status\":\"success\",\"data\":[{\"id\":1,\"reference\":\"PB-REF-1\",\"status\":\"PENDING\"}]}");
        assertThat(gateway.verifyPayout("PB-REF-1").status()).isEqualTo(PayoutStatus.PENDING);
    }

    @Test
    void verifyUnknownReferenceIsNotFound() {
        answer(200, "{\"status\":\"success\",\"data\":[]}");
        assertThat(gateway.verifyPayout("PB-REF-1").status()).isEqualTo(PayoutStatus.NOT_FOUND);
    }

    @Test
    void accountLookupReturnsNameOrNotFound() {
        answer(200, "{\"status\":\"success\",\"data\":{\"account_number\":\"0690000031\",\"account_name\":\"ADA OBI\"}}");
        assertThat(gateway.verifyAccount("NG", "044", "0690000031").accountName()).isEqualTo("ADA OBI");
        answer(400, "{\"status\":\"error\",\"message\":\"Unable to resolve account\"}");
        assertThatThrownBy(() -> gateway.verifyAccount("NG", "044", "0000000000"))
                .isInstanceOf(AccountNotFoundException.class);
        answer(401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
        assertThatThrownBy(() -> gateway.verifyAccount("NG", "044", "0000000000"))
                .isInstanceOf(VerificationUnavailableException.class);
    }
}