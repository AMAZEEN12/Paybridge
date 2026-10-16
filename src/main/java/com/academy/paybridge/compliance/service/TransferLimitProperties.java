package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for non-NGN transfers and payouts, in whole US dollars, under paybridge.limits in application.yml.
 * Same prefix as the conversion limits; each record binds only its own keys.
 */
@ConfigurationProperties(prefix = "paybridge.limits")
public record TransferLimitProperties(
        long transferMaxUsd,
        long payoutMaxUsd,
        long dailyMaxUsd) {

    public TransferLimitProperties {
        if (transferMaxUsd <= 0) {
            transferMaxUsd = 1000;
        }
        if (payoutMaxUsd <= 0) {
            payoutMaxUsd = 1000;
        }
        if (dailyMaxUsd <= 0) {
            dailyMaxUsd = 5000;
        }
    }
}