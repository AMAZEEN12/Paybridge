package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.geo.ClientCountryResolver;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CountryInfo;
import com.academy.paybridge.shared.money.CountryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sign-up. Works out the country first (this may call the geo-IP service, so no database transaction is open yet),
 * refuses unsupported countries, and only then lets AuthService create the customer and the account in one transaction.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    public record Result(CustomerView customer, String country, String countryName, String homeCurrency) {
    }

    private final ClientCountryResolver resolver;
    private final CountryRegistry countries;
    private final AuthService auth;

    public RegistrationService(ClientCountryResolver resolver, CountryRegistry countries, AuthService auth) {
        this.resolver = resolver;
        this.countries = countries;
        this.auth = auth;
    }

    /** The request header the controller must read to get the client address (empty: use the peer address). */
    public String clientIpHeader() {
        return resolver.clientIpHeader();
    }

    public Result register(String fullName, String email, String password,
                           String clientIpHeaderValue, String remoteAddress) {
        String code = resolver.countryOf(clientIpHeaderValue, remoteAddress);
        CountryInfo country = countries.supported(code).orElseThrow(() -> {
            // The country code only, never the IP address.
            log.warn("Sign-up refused: detected country code {} is not supported", code);
            return ApiException.unprocessable(
                    "COUNTRY_NOT_SUPPORTED", "PayBridge is not available in your country yet.");
        });
        CustomerView customer = auth.register(fullName, email, password, country);
        return new Result(customer, country.code(), country.name(), country.currencyCode());
    }
}