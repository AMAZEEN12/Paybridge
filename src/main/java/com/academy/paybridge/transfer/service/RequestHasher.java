package com.academy.paybridge.transfer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A fingerprint of a transfer request. The same Idempotency-Key with a different fingerprint is refused. */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(String type, String source, String destination, String bankCode,
                       long amountKobo, String narration) {
        String canonical = type + "|" + source + "|" + destination + "|" + (bankCode == null ? "" : bankCode)
                + "|" + amountKobo + "|" + (narration == null ? "" : narration.trim());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
