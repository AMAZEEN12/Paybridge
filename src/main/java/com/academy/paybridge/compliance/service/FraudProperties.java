package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every threshold lives in application.yml under paybridge.fraud, so rules change without code changes. */
@ConfigurationProperties(prefix = "paybridge.fraud")
public record FraudProperties(
        long maxSingleTransferKobo,
        long maxDailyOutgoingKobo,
        int velocityMaxTransfers,
        int velocityWindowMinutes,
        long newBeneficiaryFlagKobo,
        int passThroughWindowMinutes,
        double passThroughRatio,
        int pinMaxAttempts,
        int pinLockMinutes) {
}
