package com.academy.paybridge.remittance.client;

import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The real Flutterwave client (step B4), active when paybridge.payouts.provider is "flutterwave". Written from the
 * Flutterwave v3 API reference; the points marked (sandbox) must still be confirmed with a test key (O8).
 *
 *  - O1a  GET /transfers/rates returns a rate but NO rate id and no expiry, so nothing is locked by the partner.
 *         getRate() returns no providerRef, which keeps the quote in OWN_LOCK.
 *  - O22  POST /transfers takes the amount in the DESTINATION currency ("currency") and the wallet to debit
 *         ("debit_currency"), so the recipient gets exactly the amount PayBridge promised.
 *  - O21  Webhooks carry the secret hash in the "verif-hash" header (checked in FlutterwaveWebhookController).
 *  - Status by our own reference: GET /transfers?reference=...
 *
 *   4xx answer          -> we KNOW it did not go   -> PayoutRejectedException (refund)
 *   timeout / 5xx / IO  -> we do NOT know          -> PayoutUnavailableException (stay PENDING)
 *   a 4xx saying the reference already exists means the first request DID arrive -> PENDING, never refunded.
 * The secret key, account numbers and raw bodies are never logged.
 */
@Component
@ConditionalOnProperty(name = "paybridge.payouts.provider", havingValue = "flutterwave")
public class FlutterwavePayoutGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(FlutterwavePayoutGateway.class);

    private final RestClient client;
    private final CurrencyRegistry currencies;

    public FlutterwavePayoutGateway(PayoutProperties props, CurrencyRegistry currencies) {
        PayoutProperties.Flutterwave fw = props.flutterwave();
        if (fw.secretKey() == null || fw.secretKey().isBlank()) {
            throw new IllegalStateException(
                    "FLUTTERWAVE_SECRET_KEY must be set when paybridge.payouts.provider is 'flutterwave'. Use your TEST secret key.");
        }
        this.currencies = currencies;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(fw.connectTimeoutMs());
        factory.setReadTimeout(fw.readTimeoutMs());
        this.client = RestClient.builder()
                .baseUrl(fw.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + fw.secretKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.warn("PAYOUTS: the REAL Flutterwave client is active ({}). Use a TEST key until the sandbox checks are done.",
                fw.baseUrl());
    }

    // ---------- response shapes (only the fields we use) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(String status, String message, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateData(BigDecimal rate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountData(@JsonProperty("account_name") String accountName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferData(Long id, String reference, String status,
                        @JsonProperty("complete_message") String completeMessage) {
    }

    // ---------- the four calls ----------

    @Override
    public VerifiedName verifyAccount(String country, String bankCode, String accountNumber) {
        if (bankCode == null || bankCode.isBlank()) {
            throw new VerificationUnavailableException("Flutterwave needs a bank code to verify an account.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("account_number", accountNumber);
        request.put("account_bank", bankCode);
        try {
            Envelope<AccountData> body = send("verify account", () -> client.post()
                    .uri("/accounts/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                        throw new Rejected(res.getStatusCode().value(), read(res.getBody()));
                    })
                    .body(new ParameterizedTypeReference<Envelope<AccountData>>() { }));
            if (body == null || body.data() == null || body.data().accountName() == null
                    || body.data().accountName().isBlank()) {
                throw new AccountNotFoundException("Account not found.");
            }
            return new VerifiedName(body.data().accountName().trim());
        } catch (Rejected e) {
            // 401, 403 and 429 are about us (key, allowance), not about the account: only "cannot verify".
            if (e.status == 401 || e.status == 403 || e.status == 429) {
                throw new VerificationUnavailableException("Flutterwave refused the lookup (HTTP " + e.status + ").");
            }
            throw new AccountNotFoundException("Account not found.");
        } catch (Unreachable e) {
            throw new VerificationUnavailableException(e.getMessage());
        }
    }

    @Override
    public ProviderRate getRate(String sourceCurrency, String destinationCurrency) {
        if (sourceCurrency.equals(destinationCurrency)) {
            return new ProviderRate(BigDecimal.ONE, null, null);
        }
        try {
            // amount is in the DESTINATION currency; the rate is the same whatever small amount we ask for.
            Envelope<RateData> body = send("get rate", () -> client.get()
                    .uri("/transfers/rates?source_currency={s}&destination_currency={d}&amount=1",
                            sourceCurrency, destinationCurrency)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                        throw new Rejected(res.getStatusCode().value(), read(res.getBody()));
                    })
                    .body(new ParameterizedTypeReference<Envelope<RateData>>() { }));
            if (body == null || body.data() == null || body.data().rate() == null || body.data().rate().signum() <= 0) {
                throw new RateUnavailableException("Flutterwave returned no rate.");
            }
            return new ProviderRate(body.data().rate(), null, null);     // no rate id, no expiry: OWN_LOCK (O1a)
        } catch (Rejected | Unreachable e) {
            throw new RateUnavailableException("No Flutterwave rate for " + sourceCurrency + " to " + destinationCurrency + ".");
        }
    }

    @Override
    public PayoutResult initiatePayout(PayoutRequest r) {
        BigDecimal amount = BigDecimal.valueOf(r.destinationAmountMinor(),
                currencies.require(r.destinationCurrency()).minorUnits());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("account_bank", r.bankCode() == null ? "" : r.bankCode());
        request.put("account_number", r.accountNumber());
        request.put("amount", amount);                                   // exact destination amount (O22)
        request.put("currency", r.destinationCurrency());                // what the bank receives
        request.put("debit_currency", r.sourceCurrency());               // the wallet Flutterwave takes it from
        request.put("narration", "PayBridge payout " + r.purpose());
        request.put("reference", r.reference());                         // OUR reference: a retry cannot pay twice
        request.put("beneficiary_name", r.accountName());
        if (!"NGN".equals(r.destinationCurrency())) {
            request.put("meta", List.of(Map.of("beneficiary_country", r.country())));   // (sandbox) confirm meta keys
        }
        try {
            Envelope<TransferData> body = send("initiate payout", () -> client.post()
                    .uri("/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                        throw new Rejected(res.getStatusCode().value(), read(res.getBody()));
                    })
                    .body(new ParameterizedTypeReference<Envelope<TransferData>>() { }));
            if (body == null || body.data() == null) {
                throw new PayoutUnavailableException("Flutterwave returned no transfer data.");
            }
            log.info("Flutterwave accepted payout {} with status '{}'", r.reference(), body.data().status());
            return new PayoutResult(map(body.data().status()), providerRef(body.data()),
                    body.data().completeMessage() != null ? body.data().completeMessage() : body.message(),
                    false, null);
        } catch (Rejected e) {
            if (looksLikeDuplicateReference(e.message)) {
                // The first request DID reach Flutterwave. Never refund on this: report it as still pending.
                return PayoutResult.of(PayoutStatus.PENDING, null, "Already received");
            }
            throw new PayoutRejectedException("Flutterwave rejected the payout (HTTP " + e.status + "): " + cut(e.message));
        } catch (Unreachable e) {
            throw new PayoutUnavailableException(e.getMessage());
        }
    }

    @Override
    public PayoutResult verifyPayout(String reference) {
        try {
            Envelope<List<TransferData>> body = send("verify payout", () -> client.get()
                    .uri("/transfers?reference={ref}", reference)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, res) -> {
                        throw new Rejected(res.getStatusCode().value(), read(res.getBody()));
                    })
                    .body(new ParameterizedTypeReference<Envelope<List<TransferData>>>() { }));
            if (body == null || body.data() == null) {
                throw new PayoutUnavailableException("Flutterwave returned no data.");
            }
            TransferData found = body.data().stream()
                    .filter(t -> reference.equals(t.reference())).findFirst().orElse(null);
            if (found == null) {
                return PayoutResult.of(PayoutStatus.NOT_FOUND, null, "Flutterwave has no record of this reference");
            }
            return new PayoutResult(map(found.status()), providerRef(found), found.completeMessage(), false, null);
        } catch (Rejected e) {
            if (e.status == 404) {
                return PayoutResult.of(PayoutStatus.NOT_FOUND, null, "Flutterwave has no record of this reference");
            }
            throw new PayoutUnavailableException("Cannot verify the payout right now (HTTP " + e.status + ").");
        } catch (Unreachable e) {
            throw new PayoutUnavailableException(e.getMessage());
        }
    }

    // ---------- helpers ----------

    /** SUCCESSFUL is paid, FAILED is not; NEW, PENDING and anything unknown means "not decided yet". */
    static PayoutStatus map(String status) {
        if (status == null) {
            return PayoutStatus.PENDING;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESSFUL", "SUCCESS" -> PayoutStatus.SUCCESS;
            case "FAILED" -> PayoutStatus.FAILED;
            default -> PayoutStatus.PENDING;
        };
    }

    static boolean looksLikeDuplicateReference(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("reference") && (m.contains("exist") || m.contains("duplicate") || m.contains("already"));
    }

    private static String providerRef(TransferData t) {
        return t.id() != null ? String.valueOf(t.id()) : t.reference();
    }

    /** A 4xx answer: we know it did not work. */
    private static final class Rejected extends RuntimeException {
        final int status;
        final String message;

        Rejected(int status, String message) {
            super("HTTP " + status);
            this.status = status;
            this.message = message;
        }
    }

    /** A timeout, a 5xx or a network problem: we do not know what happened. */
    private static final class Unreachable extends RuntimeException {
        Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private <T> T send(String what, Supplier<T> action) {
        try {
            return action.get();
        } catch (Rejected | PayoutUnavailableException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Flutterwave {} failed with HTTP {}", what, e.getStatusCode().value());
            throw new Unreachable("Flutterwave error (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.warn("Flutterwave {} did not complete: {}", what, e.getClass().getSimpleName());
            throw new Unreachable("Could not reach Flutterwave (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static String read(InputStream body) {
        try {
            return new String(body.readNBytes(2000), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String cut(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) : one;
    }
}