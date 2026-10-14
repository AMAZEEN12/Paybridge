package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.api.RateProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Open Exchange Rates, free plan: USD base, 1,000 requests a month. The app id travels in the query string, so error
 * messages from the HTTP layer (which can contain the URL) are replaced by a short message with no URL.
 * The usage endpoint does not count against the quota.
 */
public class OpenExchangeRatesClient implements RateProvider {

    private final RestClient client;
    private final String appId;

    public OpenExchangeRatesClient(String baseUrl, String appId, int timeoutMs) {
        this(build(baseUrl, timeoutMs), appId);
    }

    /** Used by tests, which pass a client wired to a mock server. */
    OpenExchangeRatesClient(RestClient client, String appId) {
        this.client = client;
        this.appId = appId;
    }

    private static RestClient build(String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "openexchangerates";
    }

    @Override
    public ProviderRates fetch(Collection<String> codes) {
        LatestResponse body;
        try {
            body = client.get()
                    .uri("/latest.json?app_id={id}&symbols={symbols}", appId, String.join(",", codes))
                    .retrieve().body(LatestResponse.class);
        } catch (RuntimeException e) {
            throw failure(e);
        }
        if (body == null || body.rates() == null || body.rates().isEmpty()) {
            throw new IllegalStateException("Open Exchange Rates sent no rates");
        }
        return new ProviderRates(Instant.ofEpochSecond(body.timestamp()), body.rates());
    }

    @Override
    public ProviderUsage usage() {
        UsageResponse body;
        try {
            body = client.get().uri("/usage.json?app_id={id}", appId).retrieve().body(UsageResponse.class);
        } catch (RuntimeException e) {
            throw failure(e);
        }
        if (body == null || body.data() == null || body.data().usage() == null) {
            throw new IllegalStateException("Open Exchange Rates sent no usage figures");
        }
        return new ProviderUsage(body.data().usage().requests(), body.data().usage().requestsQuota());
    }

    private static IllegalStateException failure(RuntimeException e) {
        String detail = e instanceof RestClientResponseException r
                ? "HTTP " + r.getStatusCode().value()
                : e.getClass().getSimpleName();
        return new IllegalStateException("Open Exchange Rates request failed: " + detail);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatestResponse(long timestamp, Map<String, BigDecimal> rates) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageResponse(UsageData data) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageData(UsageStats usage) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageStats(long requests, @JsonProperty("requests_quota") long requestsQuota) { }
}