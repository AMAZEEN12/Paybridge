package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.remittance.service.PayoutSettlementService;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Flutterwave calls this when a payout changes state. Three rules (R-PAY-12, R-PAY-12a):
 *  1. Prove it is Flutterwave: the verif-hash header must equal our secret hash, compared in constant time. With no
 *     secret configured every call is refused. The scheme is to be checked against the current API reference (O21).
 *  2. Treat the body as a HINT: we ask the partner for the real status before changing anything.
 *  3. Be safe to receive twice: settlement only changes a payout that is still PENDING.
 * The secret and the raw body are never logged.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Hidden
public class FlutterwaveWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FlutterwaveWebhookController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(String event, Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(String reference) {
    }

    private final byte[] secret;
    private final PayoutSettlementService settlement;
    private final RemittanceRepository remittances;
    private final AuditService audit;

    public FlutterwaveWebhookController(PayoutProperties props, PayoutSettlementService settlement,
                                        RemittanceRepository remittances, AuditService audit) {
        String configured = props.flutterwave().webhookSecret();
        this.secret = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        this.settlement = settlement;
        this.remittances = remittances;
        this.audit = audit;
    }

    @PostMapping("/flutterwave")
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "verif-hash", required = false) String hash) {
        if (!validHash(hash)) {
            audit.recordIndependently("webhook", "WEBHOOK_REJECTED", null, "flutterwave: bad or missing signature");
            throw ApiException.unauthorized("INVALID_SIGNATURE", "Invalid signature.");
        }
        Event event;
        try {
            event = JSON.readValue(new String(rawBody, StandardCharsets.UTF_8), Event.class);
        } catch (RuntimeException e) {
            return ResponseEntity.ok().build();                    // not an event we understand; do not make them retry
        }
        if (event == null || event.event() == null || event.data() == null || event.data().reference() == null
                || !event.event().startsWith("transfer")) {
            return ResponseEntity.ok().build();
        }
        String reference = event.data().reference();
        audit.recordIndependently("flutterwave", "WEBHOOK_RECEIVED", reference, event.event());
        if (!settlement.knows(reference)) {
            return ResponseEntity.ok().build();                    // unknown reference: ignore
        }
        try {
            settlement.settleFromGateway(reference);               // asks the partner for the truth, then settles once
        } catch (RuntimeException e) {
            log.warn("Flutterwave webhook could not settle a payout: {}", e.getClass().getSimpleName());
        }
        return ResponseEntity.ok().build();
    }

    private boolean validHash(String hash) {
        if (secret.length == 0 || hash == null) {
            return false;
        }
        return MessageDigest.isEqual(secret, hash.getBytes(StandardCharsets.UTF_8));   // constant time
    }
}