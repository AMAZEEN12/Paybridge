package com.academy.paybridge.remittance.service;

import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * The countries a payout can go to (R-PAY-16). A country is listed only if its currency is usable: in the currency
 * table, enabled and not excluded (O6).
 */
@Service
public class PayoutDestinationsService {

    private final PayoutProperties props;
    private final CurrencyRegistry currencies;

    public PayoutDestinationsService(PayoutProperties props, CurrencyRegistry currencies) {
        this.props = props;
        this.currencies = currencies;
    }

    public List<PayoutProperties.Country> countries() {
        return props.countries().stream().filter(c -> currencies.isUsable(c.currency())).toList();
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