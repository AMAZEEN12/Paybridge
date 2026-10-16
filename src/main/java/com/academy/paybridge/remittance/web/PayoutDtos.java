package com.academy.paybridge.remittance.web;

import com.academy.paybridge.fx.api.PayoutQuoteApi.PayoutQuoteView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.remittance.service.PayoutQuoteService;
import com.academy.paybridge.remittance.service.VerifiedAccount;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PayoutDtos {

    private PayoutDtos() {
    }

    // ----------------------------------------------------------------- verification (B2)

    public record VerifyAccountRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter country code") @Schema(example = "NG")
            String country,
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") @Schema(example = "NGN")
            String currency,
            @Size(max = 20) @Schema(description = "Bank code, sort code or routing number, where required", example = "058")
            String bankCode,
            @NotBlank @Size(max = 40) @Schema(example = "0123456789")
            String accountNumber,
            @Schema(description = "Additional bank details listed by GET /payouts/destinations")
            Map<String, String> bankFields) {

        @Override
        public String toString() {
            return "VerifyAccountRequest[country=" + country + "]";
        }
    }

    public record VerifyAccountResponse(String accountName, String source, String label, boolean verified,
                                        String accountMasked, String country, String currency) {
        public static VerifyAccountResponse from(VerifiedAccount a) {
            return new VerifyAccountResponse(a.accountName(), a.source().name(), a.label(), a.verified(),
                    a.accountMasked(), a.country(), a.currency());
        }
    }

    public record FieldDto(String name, String label, boolean required) {
    }

    public record CountryDto(String code, String name, String currency, List<FieldDto> bankFields) {
        public static CountryDto from(PayoutProperties.Country c) {
            return new CountryDto(c.code(), c.name(), c.currency(),
                    c.bankFields().stream().map(f -> new FieldDto(f.name(), f.label(), f.required())).toList());
        }
    }

    public record DestinationsResponse(List<CountryDto> countries, List<String> purposes) {
    }

    // ----------------------------------------------------------------- quote (B3)

    public record CreatePayoutQuoteRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") @Schema(example = "GBP")
            String sourceCurrency,
            @NotNull @DecimalMin("0.01") @Schema(description = "In the source currency. The fee comes out of this amount.", example = "100.00")
            BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter country code") @Schema(example = "NG")
            String country,
            @Size(max = 20) @Schema(example = "058")
            String bankCode,
            @NotBlank @Size(max = 40) @Schema(example = "0123456789")
            String accountNumber,
            Map<String, String> bankFields,
            @NotBlank @Pattern(regexp = "(?i)INDIVIDUAL|CORPORATE", message = "must be INDIVIDUAL or CORPORATE")
            @Schema(example = "INDIVIDUAL")
            String recipientType,
            @NotBlank @Size(max = 30) @Schema(example = "FAMILY_SUPPORT")
            String purpose) {

        @Override
        public String toString() {
            return "CreatePayoutQuoteRequest[sourceCurrency=" + sourceCurrency + ", country=" + country
                    + ", purpose=" + purpose + "]";                          // never the account number
        }
    }

    public record DestinationDto(String country, String accountMasked, String accountName, String nameSource,
                                 String nameLabel, String recipientType, String purpose) {
    }

    public record PayoutQuoteResponse(UUID quoteId, String sourceCurrency, String payoutCurrency, BigDecimal sourceAmount,
                                      BigDecimal fee, String feeReason, BigDecimal netAmount, BigDecimal payoutAmount,
                                      BigDecimal rate, String rateMode, String status, Instant expiresAt,
                                      long ttlSeconds, DestinationDto destination) {

        public static PayoutQuoteResponse from(PayoutQuoteService.Result result, CurrencyRegistry currencies, Instant now) {
            PayoutQuoteView q = result.quote();
            int src = currencies.require(q.sourceCurrency()).minorUnits();
            int dst = currencies.require(q.payoutCurrency()).minorUnits();
            VerifiedAccount who = result.recipient();
            return new PayoutQuoteResponse(q.id(), q.sourceCurrency(), q.payoutCurrency(),
                    BigDecimal.valueOf(q.sourceAmountMinor(), src), BigDecimal.valueOf(q.feeMinor(), src), "STANDARD",
                    BigDecimal.valueOf(q.netMinor(), src), BigDecimal.valueOf(q.payoutAmountMinor(), dst),
                    q.rate(), q.rateMode(), q.status(), q.expiresAt(),
                    Math.max(0, Duration.between(now, q.expiresAt()).toSeconds()),
                    new DestinationDto(q.country(), who.accountMasked(), q.accountName(), who.source().name(),
                            who.label(), q.recipientType(), q.purpose()));
        }
    }

    // ----------------------------------------------------------------- confirm and read (B3)

    public record ConfirmPayoutRequest(
            @NotNull UUID quoteId,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") @Schema(example = "1234")
            String pin) {

        @Override
        public String toString() {
            return "ConfirmPayoutRequest[quoteId=" + quoteId + "]";          // never the PIN
        }
    }

    /** {@code status} is PENDING, SUCCESSFUL or FAILED only (S14); a payout that is retrying shows as PENDING. */
    public record PayoutResponse(String reference, String status, String sourceCurrency, BigDecimal sourceAmount,
                                 BigDecimal fee, String payoutCurrency, BigDecimal payoutAmount, String purpose,
                                 String failureReason, Instant createdAt, Instant completedAt) {

        public static PayoutResponse from(Remittance r, CurrencyRegistry currencies) {
            int src = currencies.require(r.getSourceCurrency()).minorUnits();
            int dst = currencies.require(r.getPayoutCurrency()).minorUnits();
            String status = r.getStatus() == RemittanceStatus.RETRYING ? RemittanceStatus.PENDING.name() : r.getStatus().name();
            return new PayoutResponse(r.getReference(), status, r.getSourceCurrency(),
                    BigDecimal.valueOf(r.getSourceAmountMinor(), src), BigDecimal.valueOf(r.getFeeMinor(), src),
                    r.getPayoutCurrency(), BigDecimal.valueOf(r.getPayoutAmountMinor(), dst), r.getPurpose(),
                    r.getFailureReason(), r.getCreatedAt(), r.getCompletedAt());
        }
    }
}