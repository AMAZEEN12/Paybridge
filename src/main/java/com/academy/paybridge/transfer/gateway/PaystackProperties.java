package com.academy.paybridge.transfer.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The secret key comes from the PAYSTACK_SECRET_KEY environment variable and is never logged. */
@ConfigurationProperties(prefix = "paybridge.paystack")
public record PaystackProperties(String baseUrl, String secretKey, int connectTimeoutMs, int readTimeoutMs) {

    @Override
    public String toString() {
        return "PaystackProperties[baseUrl=" + baseUrl + ", secretKey=***]";
    }
}
