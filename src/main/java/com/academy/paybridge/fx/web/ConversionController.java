package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.fx.service.QuoteService;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/v1/conversions")
@ConditionalOnProperty(name = "paybridge.conversion.enabled", havingValue = "true")
@Tag(name = "4. Conversions")
public class ConversionController {

    private final QuoteService quoteService;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public ConversionController(QuoteService quoteService, CurrencyRegistry currencies, Clock clock) {
        this.quoteService = quoteService;
        this.currencies = currencies;
        this.clock = clock;
    }

    @PostMapping("/quotes")
    @Operation(summary = "Request a locked quote to convert money between two active wallets",
            description = "Returns a quote locked for 60 seconds. Fee is 0 for the first 30 days of registration.")
    public ConversionDtos.QuoteResponse createQuote(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConversionDtos.CreateQuoteRequest request) {
        Long customerId = CurrentUser.id(jwt);
        Quote quote = quoteService.createConversionQuote(
                customerId, request.sourceCurrency(), request.targetCurrency(), request.amount());
        return ConversionDtos.QuoteResponse.from(quote, currencies, clock.instant());
    }

    @PostMapping
    @Operation(summary = "Confirm a conversion using a locked quote",
            description = "Debits the source wallet, credits the target wallet, and records a balanced ledger journal in one atomic transaction.")
    public ConversionDtos.ConversionResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConversionDtos.ConfirmConversionRequest request) {
        Long customerId = CurrentUser.id(jwt);
        Quote completed = quoteService.confirm(request.quoteId(), customerId);
        return ConversionDtos.ConversionResponse.from(completed, currencies);
    }
}
