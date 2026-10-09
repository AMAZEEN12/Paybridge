package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(String accountNumber, BigDecimal balance, String currency,
                                  AccountStatus status, Instant createdAt) {
        public static AccountResponse from(AccountView v) {
            return new AccountResponse(v.accountNumber(), Money.toNaira(v.balanceKobo()), "NGN",
                    v.status(), v.createdAt());
        }
    }

    public record FundRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "50000.00")
            BigDecimal amount) {
    }

    public record PinRequest(
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") @Schema(example = "1234")
            String pin) {
        @Override
        public String toString() {
            return "PinRequest[***]";
        }
    }
}
