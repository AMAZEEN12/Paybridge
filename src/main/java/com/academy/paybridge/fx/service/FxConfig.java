package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.client.OpenExchangeRatesClient;
import com.academy.paybridge.fx.client.StubRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Locale;

@Configuration
public class FxConfig {

    private static final Logger log = LoggerFactory.getLogger(FxConfig.class);

    @Bean
    public RateProvider rateProvider(FxProperties props, Clock clock) {
        String which = props.provider().trim().toLowerCase(Locale.ROOT);
        if ("stub".equals(which)) {
            log.info("Exchange rates come from the built-in stub provider (no real API calls).");
            return new StubRateProvider(clock);
        }
        if ("openexchangerates".equals(which)) {
            if (props.appId() == null || props.appId().isBlank()) {
                throw new IllegalStateException("OXR_APP_ID must be set when paybridge.fx.provider is openexchangerates");
            }
            return new OpenExchangeRatesClient(props.baseUrl(), props.appId(), props.timeoutMs());
        }
        throw new IllegalStateException("Unknown paybridge.fx.provider '" + props.provider()
                + "'. Use stub or openexchangerates.");
    }
}