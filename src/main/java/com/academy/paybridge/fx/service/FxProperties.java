package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Everything under paybridge.fx in application.yml. Missing values fall back to the spec defaults. */
@ConfigurationProperties(prefix = "paybridge.fx")
public record FxProperties(
        String provider,
        String baseUrl,
        String appId,
        int timeoutMs,
        Duration refreshInterval,
        Duration maxSnapshotAge,
        Duration onDemandRefreshMinGap,
        int quotaStopPercent) {

    public FxProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stub";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://openexchangerates.org/api";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
        if (refreshInterval == null) {
            refreshInterval = Duration.ofHours(1);
        }
        if (maxSnapshotAge == null) {
            maxSnapshotAge = Duration.ofHours(3);
        }
        if (onDemandRefreshMinGap == null) {
            onDemandRefreshMinGap = Duration.ofMinutes(10);
        }
        if (quotaStopPercent <= 0) {
            quotaStopPercent = 95;
        }
    }
}