package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.remittance.service.VerifiedAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class PayoutDtos {

    private PayoutDtos() {
    }

    public record VerifyAccountRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter country code") @Schema(example = "NG")
            String country,

            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") @Schema(example = "NGN")
            String currency,

            @Size(max = 20) @Schema(description = "Bank code, sort code or routing number, where the country needs one", example = "058")
            String bankCode,

            @NotBlank @Size(max = 40) @Schema(example = "0123456789")
            String accountNumber,

            @Schema(description = "Any other detail the country needs (see GET /payouts/destinations)")
            Map<String, String> bankFields) {

        @Override
        public String toString() {
            return "VerifyAccountRequest[country=" + country + "]";          // never the account number
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
}