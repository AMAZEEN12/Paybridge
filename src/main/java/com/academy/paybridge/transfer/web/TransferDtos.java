package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.charges.Charges;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record InternalTransferRequest(
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") @Schema(example = "2000000001")
            String sourceAccountNumber,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") @Schema(example = "2000000002")
            String destinationAccountNumber,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2, message = "at most 2 decimal places")
            @Schema(example = "5000.00")
            BigDecimal amount,
            @Size(max = 140) @Schema(example = "Rent") String narration,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") @Schema(example = "1234")
            String pin) {
        @Override
        public String toString() {
            return "InternalTransferRequest[amount=" + amount + "]";
        }
    }

    public record ExternalTransferRequest(
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") @Schema(example = "2000000001")
            String sourceAccountNumber,
            @NotBlank @Pattern(regexp = "[0-9A-Za-z]{2,12}", message = "invalid bank code")
            @Schema(description = "Take this from GET /api/v1/banks", example = "033")
            String bankCode,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") @Schema(example = "0123456789")
            String destinationAccountNumber,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2, message = "at most 2 decimal places")
            @Schema(example = "2000.00")
            BigDecimal amount,
            @Size(max = 140) @Schema(example = "School fees") String narration,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") @Schema(example = "1234")
            String pin) {
        @Override
        public String toString() {
            return "ExternalTransferRequest[amount=" + amount + "]";
        }
    }

    public record QuoteRequest(
            @NotNull TransferType type,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2, message = "at most 2 decimal places")
            @Schema(example = "20000.00")
            BigDecimal amount) {
    }

    public record QuoteResponse(BigDecimal amount, BigDecimal fee, BigDecimal vat, BigDecimal stampDuty,
                                BigDecimal totalCharges, BigDecimal totalDebit) {
        public static QuoteResponse of(long amountKobo, Charges c) {
            return new QuoteResponse(Money.toNaira(amountKobo), Money.toNaira(c.feeKobo()), Money.toNaira(c.vatKobo()),
                    Money.toNaira(c.stampDutyKobo()), Money.toNaira(c.totalKobo()),
                    Money.toNaira(amountKobo + c.totalKobo()));
        }
    }

    public record TransferResponse(
            String reference, TransferType type, TransferStatus status,
            String sourceAccountNumber, String destinationAccountNumber,
            String destinationBankCode, String destinationBankName, String destinationAccountName,
            BigDecimal amount, BigDecimal fee, BigDecimal vat, BigDecimal stampDuty, BigDecimal totalDebit,
            String narration, String note, Instant createdAt, Instant updatedAt) {

        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getReference(), t.getType(), t.getStatus(),
                    t.getSourceAccountNumber(), t.getDestinationAccountNumber(),
                    t.getDestinationBankCode(), t.getDestinationBankName(), t.getDestinationAccountName(),
                    Money.toNaira(t.getAmountKobo()), Money.toNaira(t.getFeeKobo()), Money.toNaira(t.getVatKobo()),
                    Money.toNaira(t.getStampDutyKobo()), Money.toNaira(t.getTotalDebitKobo()),
                    t.getNarration(), t.getNote(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record BankResponse(String name, String code) {
    }

    public record ResolveRequest(
            @NotBlank @Pattern(regexp = "[0-9A-Za-z]{2,12}") String bankCode,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "must be 10 digits") String accountNumber) {
    }

    public record ResolveResponse(String accountName) {
    }
}
