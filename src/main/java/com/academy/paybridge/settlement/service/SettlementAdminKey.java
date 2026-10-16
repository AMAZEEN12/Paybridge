package com.academy.paybridge.settlement.service;

import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The operator check every settlement endpoint uses (R-SET-12): no key configured means the endpoint is off (404);
 * a missing or wrong X-Admin-Key is refused (403). A separate setting from the reconciliation key. Constant-time compare.
 */
@Component
public class SettlementAdminKey {

    private final byte[] key;

    public SettlementAdminKey(SettlementProperties props) {
        this.key = props.adminKey().getBytes(StandardCharsets.UTF_8);
    }

    public void require(String given) {
        if (key.length == 0) {
            throw ApiException.notFound("NOT_FOUND", "Not found.");
        }
        byte[] supplied = given == null ? new byte[0] : given.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(key, supplied)) {
            throw ApiException.forbidden("ADMIN_KEY_REQUIRED", "This action is for operators only.");
        }
    }
}