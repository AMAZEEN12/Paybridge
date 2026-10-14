package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.domain.Quote;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ConversionDtos {

    private ConversionDtos() {
    }

    public record CreateQuoteRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code")
            @Schema(example = "NGN")
            String sourceCurrency,

            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code")
            @Schema(example = "USD")
            String targetCurrency,

            @NotNull @DecimalMin("0.01")
            @Schema(example = "10000.00")
            BigDecimal amount) {
    }

    public record QuoteResponse(
            UUID id,
            String sourceCurrency,
            String targetCurrency,
            BigDecimal sourceAmount,
            String sourceDisplay,
            BigDecimal fee,
            String feeDisplay,
            String feeReason,
            BigDecimal netAmount,
            String netDisplay,
            BigDecimal targetAmount,
            String targetDisplay,
            BigDecimal rate,
            String rateLabel,
            String status,
            Instant expiresAt,
            long ttlSeconds) {

        public static QuoteResponse from(Quote q, CurrencyRegistry currencies, Instant now) {
            var src = currencies.find(q.getSourceCurrency()).orElseThrow();
            var tgt = currencies.find(q.getTargetCurrency()).orElseThrow();

            BigDecimal srcAmt = BigDecimal.valueOf(q.getSourceAmountMinor(), src.minorUnits());
            BigDecimal feeAmt = BigDecimal.valueOf(q.getFeeMinor(), src.minorUnits());
            BigDecimal netAmt = BigDecimal.valueOf(q.getNetMinor(), src.minorUnits());
            BigDecimal tgtAmt = BigDecimal.valueOf(q.getTargetAmountMinor(), tgt.minorUnits());

            String srcDisp = currencies.format(q.getSourceAmountMinor(), q.getSourceCurrency());
            String feeDisp = currencies.format(q.getFeeMinor(), q.getSourceCurrency());
            String netDisp = currencies.format(q.getNetMinor(), q.getSourceCurrency());
            String tgtDisp = currencies.format(q.getTargetAmountMinor(), q.getTargetCurrency());

            long ttl = Math.max(0, java.time.Duration.between(now, q.getExpiresAt()).toSeconds());

            return new QuoteResponse(
                    q.getId(),
                    q.getSourceCurrency(),
                    q.getTargetCurrency(),
                    srcAmt,
                    srcDisp,
                    feeAmt,
                    feeDisp,
                    q.getFeeReason().name(),
                    netAmt,
                    netDisp,
                    tgtAmt,
                    tgtDisp,
                    q.getRate(),
                    "indicative rate",
                    q.getStatus().name(),
                    q.getExpiresAt(),
                    ttl
            );
        }
    }

    public record ConfirmConversionRequest(
            @NotNull UUID quoteId) {
    }

    public record ConversionResponse(
            UUID id,
            String sourceCurrency,
            String targetCurrency,
            BigDecimal sourceAmount,
            String sourceDisplay,
            BigDecimal fee,
            String feeDisplay,
            String feeReason,
            BigDecimal netAmount,
            String netDisplay,
            BigDecimal targetAmount,
            String targetDisplay,
            BigDecimal rate,
            String status,
            Instant completedAt,
            String journalKey) {

        public static ConversionResponse from(Quote q, CurrencyRegistry currencies) {
            var src = currencies.find(q.getSourceCurrency()).orElseThrow();
            var tgt = currencies.find(q.getTargetCurrency()).orElseThrow();

            BigDecimal srcAmt = BigDecimal.valueOf(q.getSourceAmountMinor(), src.minorUnits());
            BigDecimal feeAmt = BigDecimal.valueOf(q.getFeeMinor(), src.minorUnits());
            BigDecimal netAmt = BigDecimal.valueOf(q.getNetMinor(), src.minorUnits());
            BigDecimal tgtAmt = BigDecimal.valueOf(q.getTargetAmountMinor(), tgt.minorUnits());

            return new ConversionResponse(
                    q.getId(),
                    q.getSourceCurrency(),
                    q.getTargetCurrency(),
                    srcAmt,
                    currencies.format(q.getSourceAmountMinor(), q.getSourceCurrency()),
                    feeAmt,
                    currencies.format(q.getFeeMinor(), q.getSourceCurrency()),
                    q.getFeeReason().name(),
                    netAmt,
                    currencies.format(q.getNetMinor(), q.getSourceCurrency()),
                    tgtAmt,
                    currencies.format(q.getTargetAmountMinor(), q.getTargetCurrency()),
                    q.getRate(),
                    q.getStatus().name(),
                    q.getCompletedAt(),
                    q.getJournalKey()
            );
        }
    }
}
