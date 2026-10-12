package com.academy.paybridge.ledger.web;

import com.academy.paybridge.ledger.api.ReconciliationReport;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Operator-only. It shows company-wide totals, so a normal customer token is NOT enough: the caller must also send
 * the X-Admin-Key header. If PAYBRIDGE_RECONCILIATION_ADMIN_KEY is not set, the endpoint is off (404).
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@Tag(name = "10. Operations")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final byte[] adminKey;

    public ReconciliationController(ReconciliationService reconciliation,
                                    @Value("${paybridge.reconciliation.admin-key:}") String adminKey) {
        this.reconciliation = reconciliation;
        this.adminKey = adminKey == null ? new byte[0] : adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    @Operation(summary = "Check that the ledger and every account balance agree (operators only)")
    public ReconciliationReport run(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (adminKey.length == 0) {
            throw ApiException.notFound("NOT_FOUND", "Not found.");
        }
        byte[] given = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(adminKey, given)) {            // constant-time compare
            throw ApiException.forbidden("ADMIN_KEY_REQUIRED", "This action is for operators only.");
        }
        return reconciliation.run();
    }
}