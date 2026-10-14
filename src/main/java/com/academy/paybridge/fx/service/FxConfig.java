package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.client.OpenExchangeRatesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The app's rate provider is Open Exchange Rates. Tests use the "test" profile and get their own provider. */
@Configuration
@Profile("!test")
public class FxConfig {

    private static final Logger log = LoggerFactory.getLogger(FxConfig.class);

    @Bean
    public RateProvider rateProvider(FxProperties props) {
        if (props.appId() == null || props.appId().isBlank()) {
            throw new IllegalStateException("OXR_APP_ID must be set. PayBridge gets its exchange rates from Open Exchange Rates.");
        }
        log.info("Exchange rates come from Open Exchange Rates.");
        return new OpenExchangeRatesClient(props.baseUrl(), props.appId(), props.timeoutMs());
    }
}