package com.academy.paybridge.account.web;

import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/currencies")
@Tag(name = "3. Accounts")
public class CurrencyController {

    public record CurrencyResponse(String code, String name, String symbol, int minorUnits) {
    }

    private final CurrencyRegistry currencies;

    public CurrencyController(CurrencyRegistry currencies) {
        this.currencies = currencies;
    }

    @GetMapping
    @Operation(summary = "Currencies I can activate", description = "Enabled currencies that are not on the exclusion list.")
    public List<CurrencyResponse> usable() {
        return currencies.usable().stream()
                .map(c -> new CurrencyResponse(c.code(), c.name(), c.symbol(), c.minorUnits())).toList();
    }
}