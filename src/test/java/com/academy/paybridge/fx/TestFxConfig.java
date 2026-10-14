package com.academy.paybridge.fx;

import com.academy.paybridge.fx.api.RateProvider;
import com.academy.paybridge.fx.client.StubRateProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Fixed rates for the test suite only, so no test ever touches the real API or its quota. */
@Configuration
@Profile("test")
public class TestFxConfig {

    @Bean
    public RateProvider rateProvider(Clock clock) {
        return new StubRateProvider(clock);
    }
}