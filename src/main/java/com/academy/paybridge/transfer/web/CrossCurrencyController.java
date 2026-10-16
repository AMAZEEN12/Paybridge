package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.transfer.service.CrossCurrencyTransferService;
import com.academy.paybridge.transfer.service.TransferResult;
import com.academy.paybridge.transfer.web.CrossCurrencyDtos.ConfirmRequest;
import com.academy.paybridge.transfer.web.CrossCurrencyDtos.QuoteRequest;
import com.academy.paybridge.transfer.web.CrossCurrencyDtos.QuoteResponse;
import com.academy.paybridge.transfer.web.TransferDtos.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/transfers/cross-currency")
@Tag(name = "4. Transfers")
public class CrossCurrencyController {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{8,100}");

    private final CrossCurrencyTransferService service;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public CrossCurrencyController(CrossCurrencyTransferService service, CurrencyRegistry currencies, Clock clock) {
        this.service = service;
        this.currencies = currencies;
        this.clock = clock;
    }

    @PostMapping("/quotes")
    @Operation(summary = "Quote a transfer where the recipient gets a different currency. Changes nothing.",
            description = "Returns the rate, the fee, the exact amount they receive and their name. Locked for 30 minutes. "
                    + "Fee is 0 for the first 30 days after sign-up.")
    public QuoteResponse quote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest request) {
        return QuoteResponse.from(
                service.quote(CurrentUser.id(jwt), request.destinationAccountNumber(), request.sourceCurrency(),
                        request.receiveCurrency(), request.amount()),
                currencies, clock.instant());
    }

    @PostMapping
    @Operation(summary = "Confirm a cross-currency transfer with a locked quote",
            description = "All or nothing. 201 on success, 200 if this Idempotency-Key was already used for the same quote.")
    public ResponseEntity<TransferResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A unique value per transfer, 8 to 100 characters.", example = "xc-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmRequest request) {
        TransferResult result = service.confirm(CurrentUser.id(jwt), request.quoteId(), request.pin(),
                checkKey(idempotencyKey));
        TransferResponse body = TransferResponse.from(result.transfer(), currencies);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static String checkKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (!KEY.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8 to 100 characters: letters, digits, dash, underscore, dot or colon.");
        }
        return trimmed;
    }
}