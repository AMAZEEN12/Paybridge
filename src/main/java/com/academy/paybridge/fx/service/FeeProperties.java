package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Settings under paybridge.fee in application.yml. */
@ConfigurationProperties(prefix = "paybridge.fee")
public record FeeProperties(
        BigDecimal rate,
        int freeDays) {

    public FeeProperties {
        if (rate == null) {
            rate = new BigDecimal("0.005");
        }
        if (freeDays <= 0) {
            freeDays = 30;
        }
    }
}
