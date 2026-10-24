package com.academy.paybridge.remittance.service;

import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CountryInfo;
import com.academy.paybridge.shared.money.CountryRegistry;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The countries a payout can go to (R-PAY-16). A country is listed only if its currency is usable: in the currency
 * table, enabled and not excluded (O6).
 */
@Service
public class PayoutDestinationsService {

    private final PayoutProperties props;
    private final CurrencyRegistry currencies;
    private final CountryRegistry countryRegistry;

    public PayoutDestinationsService(PayoutProperties props, CurrencyRegistry currencies) {
        this(props, currencies, null);
    }

    @Autowired
    public PayoutDestinationsService(PayoutProperties props, CurrencyRegistry currencies,
                                     @Autowired(required = false) CountryRegistry countryRegistry) {
        this.props = props;
        this.currencies = currencies;
        this.countryRegistry = countryRegistry;
    }

    public List<PayoutProperties.Country> countries() {
        Map<String, PayoutProperties.Country> map = new LinkedHashMap<>();

        // Configured countries with specific bank fields first
        for (PayoutProperties.Country c : props.countries()) {
            if (currencies.isUsable(c.currency())) {
                map.put(c.code(), c);
            }
        }

        // Add all other supported countries known to the system
        if (countryRegistry != null) {
            for (CountryInfo c : countryRegistry.allSupported()) {
                if (!map.containsKey(c.code()) && currencies.isUsable(c.currencyCode())) {
                    map.put(c.code(), new PayoutProperties.Country(
                            c.code(),
                            c.name(),
                            c.currencyCode(),
                            List.of(
                                    new PayoutProperties.BankField("accountNumber", "Account number / IBAN", true),
                                    new PayoutProperties.BankField("bankCode", "Bank code / SWIFT", false)
                            )
                    ));
                }
            }
        }

        return new ArrayList<>(map.values());
    }

    public List<String> purposes() {
        return props.purposes();
    }

    public PayoutProperties.Country require(String code) {
        String normal = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return countries().stream().filter(c -> c.code().equals(normal)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("COUNTRY_NOT_SUPPORTED",
                        "We cannot pay out to that country yet."));
    }
}
