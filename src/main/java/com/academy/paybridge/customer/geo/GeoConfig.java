package com.academy.paybridge.customer.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeoConfig {

    @Bean
    public ClientCountryResolver clientCountryResolver(
            GeoLocator geo,
            @Value("${paybridge.geo.override-country:}") String overrideCountry,
            @Value("${paybridge.geo.allow-override:false}") boolean allowOverride,
            @Value("${paybridge.geo.client-ip-header:True-Client-IP}") String clientIpHeader) {
        return new ClientCountryResolver(geo, overrideCountry, allowOverride, clientIpHeader);
    }
}