package com.academy.paybridge.customer.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GeoLocator backed by freeipapi.com's free server: GET {base-url}/{ip} returns JSON with a "countryCode".
 * Free plan: no key, 10 requests per 10 seconds and 60 per minute, counted per server IP. Any problem (timeout,
 * 429, 5xx, bad JSON) gives an empty result, and the caller then refuses the sign-up. The IP address is never logged.
 */
@Component
public class FreeIpApiGeoLocator implements GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(FreeIpApiGeoLocator.class);

    private final RestClient client;
    private final String baseUrl;

    public FreeIpApiGeoLocator(@Value("${paybridge.geo.base-url:https://free.freeipapi.com/api/v1/json}") String baseUrl,
                               @Value("${paybridge.geo.timeout-ms:3000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<String> countryOf(String ip) {
        try {
            Map<?, ?> body = client.get().uri(baseUrl + "/{ip}", ip).retrieve().body(Map.class);
            Object code = body == null ? null : body.get("countryCode");
            if (code instanceof String s && s.matches("[A-Za-z]{2}")) {
                return Optional.of(s.toUpperCase(Locale.ROOT));
            }
            log.warn("Geo-IP answer had no usable country code");
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Geo-IP lookup failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}