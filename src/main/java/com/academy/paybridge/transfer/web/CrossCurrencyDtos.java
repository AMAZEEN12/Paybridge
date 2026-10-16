package com.academy.paybridge.transfer.web;

import com.academy.paybridge.fx.api.TransferQuoteApi.TransferQuoteView;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class CrossCurrencyDtos {

    private CrossCurrencyDtos() {
    }

    public record QuoteRequest(
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") @Schema(example = "2000000002")
            String destinationAccountNumber,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") @Schema(example = "USD")
            String sourceCurrency,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") @Schema(example = "NGN")
            String receiveCurrency,
            @NotNull @DecimalMin("0.01") @Schema(description = "In the source currency. The fee comes out of this amount.", example = "100.00")
            BigDecimal amount) {
    }

    public record QuoteResponse(
            UUID id, String recipientAccountNumber, String recipientName,
            String sourceCurrency, String receiveCurrency,
            BigDecimal sourceAmount, BigDecimal fee, String feeReason, BigDecimal netAmount,
            BigDecimal receiveAmount, String receiveDisplay,
            BigDecimal rate, String status, Instant expiresAt, long ttlSeconds) {

        public static QuoteResponse from(TransferQuoteView q, CurrencyRegistry currencies, Instant now) {
            int src = currencies.require(q.sourceCurrency()).minorUnits();
            int rcv = currencies.require(q.receiveCurrency()).minorUnits();
            return new QuoteResponse(q.id(), q.recipientAccount(), q.recipientName(),
                    q.sourceCurrency(), q.receiveCurrency(),
                    BigDecimal.valueOf(q.sourceAmountMinor(), src), BigDecimal.valueOf(q.feeMinor(), src), q.feeReason(),
                    BigDecimal.valueOf(q.netMinor(), src),
                    BigDecimal.valueOf(q.receiveAmountMinor(), rcv),
                    currencies.format(q.receiveAmountMinor(), q.receiveCurrency()),
                    q.rate(), q.status(), q.expiresAt(),
                    Math.max(0, Duration.between(now, q.expiresAt()).toSeconds()));
        }
    }

    public record ConfirmRequest(
            @NotNull UUID quoteId,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") @Schema(example = "1234")
            String pin) {
        @Override
        public String toString() {
            return "ConfirmRequest[quoteId=" + quoteId + "]";
        }
    }
}