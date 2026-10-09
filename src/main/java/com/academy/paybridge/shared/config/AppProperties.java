package com.academy.paybridge.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings that are not owned by one module. Values come from application.yml and environment variables. */
@ConfigurationProperties(prefix = "paybridge")
public record AppProperties(
        String jwtSecret,
        int jwtTtlMinutes,
        int resolveLimitPerMinute,
        String zone,
        Scheduler scheduler,
        Login login) {

    public record Scheduler(boolean enabled) {
    }

    public record Login(int maxFailedAttempts, int lockMinutes) {
    }
}
