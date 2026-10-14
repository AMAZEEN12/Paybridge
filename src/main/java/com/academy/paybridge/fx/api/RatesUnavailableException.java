package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class RatesUnavailableException extends ApiException {

    public RatesUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "RATES_UNAVAILABLE",
                "Exchange rates are temporarily unavailable. Please try again shortly.");
    }
}