package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings under paybridge.quotes in application.yml. */
@ConfigurationProperties(prefix = "paybridge.quotes")
public record QuoteProperties(
        Duration conversionTtl,
        Duration transferTtl,
        int maxActivePerCustomer,
        Duration stuckExecutingLimit) {

    public QuoteProperties {
        if (conversionTtl == null) {
            conversionTtl = Duration.ofSeconds(60);
        }
        if (transferTtl == null) {
            transferTtl = Duration.ofMinutes(30);
        }
        if (maxActivePerCustomer <= 0) {
            maxActivePerCustomer = 5;
        }
        if (stuckExecutingLimit == null) {
            stuckExecutingLimit = Duration.ofMinutes(5);
        }
    }
}
