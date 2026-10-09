package com.academy.paybridge.transfer.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to Paystack. Active only with the "paystack" profile. Endpoint paths and field names are
 * written from Paystack's documentation; check them against the current docs when you first run it.
 *
 * Error handling is the important part:
 *   4xx answer          -> GatewayRejectedException   (we KNOW it did not go)
 *   timeout / 5xx / IO  -> GatewayUnavailableException (we do NOT know)
 */
@Component
@Profile("paystack")
public class PaystackTransferGateway implements TransferGateway {

    private static final Logger log = LoggerFactory.getLogger(PaystackTransferGateway.class);

    private final RestClient client;
    /** True when name enquiry is answered here instead of by Paystack (see resolve-mode in application.yml). */
    private final boolean simulateResolve;

    public PaystackTransferGateway(PaystackProperties props,
                                   @Value("${paybridge.paystack.resolve-mode:auto}") String resolveMode) {
        if (props.secretKey() == null || props.secretKey().isBlank()) {
            throw new IllegalStateException(
                    "PAYSTACK_SECRET_KEY must be set when the 'paystack' profile is active. Use your TEST secret key.");
        }
        boolean testKey = props.secretKey().startsWith("sk_test_");
        String mode = resolveMode == null ? "auto" : resolveMode.trim().toLowerCase();
        this.simulateResolve = "simulated".equals(mode) || ("auto".equals(mode) && testKey);
        if (simulateResolve && !testKey) {
            throw new IllegalStateException("resolve-mode 'simulated' is only allowed with a TEST secret key (sk_test_...).");
        }
        if (simulateResolve) {
            log.warn("Name enquiry is SIMULATED (test key). Paystack limits live bank lookups in test mode. "
                    + "Set PAYSTACK_RESOLVE_MODE=live to call Paystack for real.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeoutMs());
        factory.setReadTimeout(props.readTimeoutMs());
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.secretKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ---------- response shapes (only the fields we use) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(boolean status, String message, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankItem(String name, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveData(@JsonProperty("account_number") String accountNumber,
                       @JsonProperty("account_name") String accountName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecipientData(@JsonProperty("recipient_code") String recipientCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferData(String reference, @JsonProperty("transfer_code") String transferCode, String status) {
    }

    // ---------- the five calls ----------

    @Override
    public List<Bank> listBanks() {
        Envelope<List<BankItem>> body = call("list banks", () -> client.get()
                .uri("/bank?country=nigeria&currency=NGN&perPage=100")
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new GatewayRejectedException(readMessage(res.getBody()), res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<Envelope<List<BankItem>>>() { }));
        if (body == null || body.data() == null) {
            throw new GatewayUnavailableException("Empty bank list from Paystack.");
        }
        return body.data().stream().filter(b -> b.code() != null && b.name() != null)
                .map(b -> new Bank(b.name(), b.code())).toList();
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        if (simulateResolve) {
            // No call to Paystack, so it cannot run out of "live lookups". The name is obviously not a real one.
            String last4 = accountNumber.length() >= 4 ? accountNumber.substring(accountNumber.length() - 4) : accountNumber;
            return new ResolvedAccount(accountNumber, "TEST ACCOUNT " + last4);
        }
        Envelope<ResolveData> body = call("resolve account", () -> client.get()
                .uri("/bank/resolve?account_number={a}&bank_code={b}", accountNumber, bankCode)
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new GatewayRejectedException(readMessage(res.getBody()), res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<Envelope<ResolveData>>() { }));
        if (body == null || body.data() == null || body.data().accountName() == null) {
            throw new GatewayRejectedException("Could not resolve account name.", 422);
        }
        return new ResolvedAccount(accountNumber, body.data().accountName());
    }

    @Override
    public String createRecipient(String accountName, String accountNumber, String bankCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "nuban");
        request.put("name", accountName);
        request.put("account_number", accountNumber);
        request.put("bank_code", bankCode);
        request.put("currency", "NGN");
        Envelope<RecipientData> body = call("create recipient", () -> client.post()
                .uri("/transferrecipient")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new GatewayRejectedException(readMessage(res.getBody()), res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<Envelope<RecipientData>>() { }));
        if (body == null || body.data() == null || body.data().recipientCode() == null) {
            throw new GatewayUnavailableException("Paystack returned no recipient code.");
        }
        return body.data().recipientCode();
    }

    @Override
    public GatewayResult initiateTransfer(String reference, long amountKobo, String recipientCode, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("source", "balance");
        request.put("amount", amountKobo);              // Paystack counts in kobo
        request.put("recipient", recipientCode);
        request.put("reference", reference);            // OUR reference: a retry cannot pay twice
        request.put("reason", reason == null ? "PayBridge transfer" : reason);
        Envelope<TransferData> body = call("initiate transfer", () -> client.post()
                .uri("/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                    throw new GatewayRejectedException(readMessage(res.getBody()), res.getStatusCode().value());
                })
                .body(new ParameterizedTypeReference<Envelope<TransferData>>() { }));
        if (body == null || body.data() == null) {
            throw new GatewayUnavailableException("Paystack returned no transfer data.");
        }
        return new GatewayResult(map(body.data().status()), body.data().transferCode(), body.message());
    }

    @Override
    public GatewayResult verifyTransfer(String reference) {
        try {
            Envelope<TransferData> body = call("verify transfer", () -> client.get()
                    .uri("/transfer/verify/{ref}", reference)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                        throw new GatewayRejectedException(readMessage(res.getBody()), res.getStatusCode().value());
                    })
                    .body(new ParameterizedTypeReference<Envelope<TransferData>>() { }));
            if (body == null || body.data() == null) {
                throw new GatewayUnavailableException("Paystack returned no transfer data.");
            }
            return new GatewayResult(map(body.data().status()), body.data().transferCode(), body.message());
        } catch (GatewayRejectedException e) {
            if (e.getHttpStatus() == 404) {
                return new GatewayResult(GatewayStatus.NOT_FOUND, null, "Paystack has no record of this reference");
            }
            throw e;
        }
    }

    // ---------- helpers ----------

    /** success -> SUCCESS, failed/reversed -> FAILED, everything else (pending, otp, received) -> PENDING. */
    static GatewayStatus map(String paystackStatus) {
        if (paystackStatus == null) {
            return GatewayStatus.PENDING;
        }
        return switch (paystackStatus.toLowerCase()) {
            case "success" -> GatewayStatus.SUCCESS;
            case "failed", "reversed" -> GatewayStatus.FAILED;
            default -> GatewayStatus.PENDING;
        };
    }

    private <T> T call(String what, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (GatewayRejectedException | GatewayUnavailableException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // A 5xx: the provider failed, and we do not know whether money moved.
            log.warn("Paystack {} failed with HTTP {}", what, e.getStatusCode().value());
            throw new GatewayUnavailableException("Paystack error (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            // Timeout or network problem: outcome unknown.
            log.warn("Paystack {} did not complete: {}", what, e.getClass().getSimpleName());
            throw new GatewayUnavailableException("Could not reach Paystack (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static String readMessage(java.io.InputStream body) {
        try {
            String text = new String(body.readNBytes(2000), StandardCharsets.UTF_8);
            return text.isBlank() ? "Rejected by Paystack." : "Paystack: " + text;
        } catch (IOException e) {
            return "Rejected by Paystack.";
        }
    }
}