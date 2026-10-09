package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.transfer.gateway.PaystackProperties;
import com.academy.paybridge.transfer.repository.TransferRepository;
import com.academy.paybridge.transfer.service.TransferSettlementService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Paystack calls this when a payout changes state. Three rules:
 *  1. Prove it is Paystack: x-paystack-signature is an HMAC-SHA512 of the RAW body with our secret key.
 *  2. Never trust the body: we ask Paystack for the real status before changing anything.
 *  3. Be safe to receive twice: settlement only changes a transfer that is still PENDING.
 * The raw body is never logged.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Hidden
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(String event, Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(String reference) {
    }

    private final String secretKey;
    private final TransferSettlementService settlement;
    private final TransferRepository transfers;
    private final AuditService audit;

    public PaystackWebhookController(PaystackProperties props, TransferSettlementService settlement,
                                     TransferRepository transfers, AuditService audit) {
        this.secretKey = props.secretKey() == null ? "" : props.secretKey();
        this.settlement = settlement;
        this.transfers = transfers;
        this.audit = audit;
    }

    @PostMapping("/paystack")
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        if (!validSignature(rawBody, signature)) {
            audit.recordIndependently("webhook", "WEBHOOK_REJECTED", null, "bad or missing signature");
            throw ApiException.unauthorized("INVALID_SIGNATURE", "Invalid signature.");
        }
        Event event;
        try {
            event = JSON.readValue(new String(rawBody, StandardCharsets.UTF_8), Event.class);
        } catch (RuntimeException e) {
            return ResponseEntity.ok().build();           // not an event we understand; do not make Paystack retry
        }
        if (event == null || event.event() == null || event.data() == null || event.data().reference() == null
                || !event.event().startsWith("transfer.")) {
            return ResponseEntity.ok().build();
        }
        String reference = event.data().reference();
        audit.recordIndependently("paystack", "WEBHOOK_RECEIVED", reference, event.event());
        if (transfers.findByReference(reference).isEmpty()) {
            log.info("Webhook for unknown reference ignored");
            return ResponseEntity.ok().build();
        }
        settlement.settleFromGateway(reference);         // asks Paystack for the truth, then settles once
        return ResponseEntity.ok().build();
    }

    public boolean validSignature(byte[] body, String signature) {
        if (secretKey.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
            byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, given);   // constant-time compare
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }
}
