package com.academy.paybridge.customer.geo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FreeIpApiGeoLocatorTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/json/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/json";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsTheCountryCodeAndAsksForTheGivenIp() {
        body = "{\"ipAddress\":\"8.8.8.8\",\"countryCode\":\"ng\",\"countryName\":\"Nigeria\"}";

        assertThat(new FreeIpApiGeoLocator(baseUrl, 2000).countryOf("8.8.8.8")).contains("NG");
        assertThat(requestedPath.get()).isEqualTo("/api/v1/json/8.8.8.8");
    }

    @Test
    void anyProblemGivesAnEmptyAnswerInsteadOfAnException() {
        FreeIpApiGeoLocator locator = new FreeIpApiGeoLocator(baseUrl, 2000);

        status = 500;
        body = "{}";
        assertThat(locator.countryOf("8.8.8.8")).isEmpty();

        status = 429;
        assertThat(locator.countryOf("8.8.8.8")).isEmpty();

        status = 200;
        body = "this is not json";
        assertThat(locator.countryOf("8.8.8.8")).isEmpty();

        body = "{\"countryName\":\"Nowhere\"}";                    // no countryCode
        assertThat(locator.countryOf("8.8.8.8")).isEmpty();

        body = "{\"countryCode\":\"NGA\"}";                        // not a two-letter code
        assertThat(locator.countryOf("8.8.8.8")).isEmpty();
    }

    @Test
    void aSlowAnswerIsGivenUpOn() {
        body = "{\"countryCode\":\"NG\"}";
        delayMs = 1500;

        assertThat(new FreeIpApiGeoLocator(baseUrl, 300).countryOf("8.8.8.8")).isEmpty();
    }
}