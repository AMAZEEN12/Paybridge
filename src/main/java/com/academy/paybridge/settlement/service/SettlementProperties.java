package com.academy.paybridge.settlement.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Everything under paybridge.settlement in application.yml. The admin key is a secret: environment only, never printed.
 * Thresholds are a list (not a map) because ledger names contain colons, which Spring would strip from map keys.
 */
@ConfigurationProperties(prefix = "paybridge.settlement")
public record SettlementProperties(String adminKey, String runAt, Duration statementLag, List<Threshold> thresholds) {

    public SettlementProperties {
        adminKey = adminKey == null ? "" : adminKey;
        runAt = runAt == null || runAt.isBlank() ? "00:30" : runAt.trim();
        statementLag = statementLag == null ? Duration.ofHours(24) : statementLag;
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
    }

    /** A warning level for one ledger account, for example {@code account: EXT:FX_POOL:USD, min-minor: 100000}. */
    public record Threshold(String account, long minMinor) {
    }

    @Override
    public String toString() {
        return "SettlementProperties[adminKey=***, runAt=" + runAt + "]";
    }
}