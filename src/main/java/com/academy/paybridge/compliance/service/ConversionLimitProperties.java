package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for conversion limits and velocity under paybridge.limits in application.yml. */
@ConfigurationProperties(prefix = "paybridge.limits")
public record ConversionLimitProperties(
        long conversionMaxUsd,
        long dailyMaxUsd,
        int velocityMaxConversions,
        int velocityWindowMinutes) {

    public ConversionLimitProperties {
        if (conversionMaxUsd <= 0) {
            conversionMaxUsd = 1000;
        }
        if (dailyMaxUsd <= 0) {
            dailyMaxUsd = 5000;
        }
        if (velocityMaxConversions <= 0) {
            velocityMaxConversions = 5;
        }
        if (velocityWindowMinutes <= 0) {
            velocityWindowMinutes = 10;
        }
    }
}
