package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.charges.Charges;
import com.academy.paybridge.transfer.service.TransferResult;
import com.academy.paybridge.transfer.service.TransferService;
import com.academy.paybridge.transfer.web.TransferDtos.ExternalTransferRequest;
import com.academy.paybridge.transfer.web.TransferDtos.InternalTransferRequest;
import com.academy.paybridge.transfer.web.TransferDtos.QuoteRequest;
import com.academy.paybridge.transfer.web.TransferDtos.QuoteResponse;
import com.academy.paybridge.transfer.web.TransferDtos.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Transfers")
public class TransferController {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{8,100}");

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/transfers/quote")
    @Operation(summary = "What will this cost me? Shows fee, VAT and stamp duty. Changes nothing.")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        long kobo = Money.toKobo(request.amount());
        Charges charges = service.quote(kobo, request.type());
        return QuoteResponse.of(kobo, charges);
    }

    @PostMapping("/transfers/internal")
    @Operation(summary = "Send money to another PayBridge account",
            description = "All or nothing. 201 on success, 200 if this Idempotency-Key was already used with the same request, "
                    + "422 if the key was used with a different request.")
    public ResponseEntity<TransferResponse> internal(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A unique value per transfer, 8 to 100 characters. Reuse it only to retry the SAME transfer.",
                    example = "demo-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InternalTransferRequest request) {
        TransferResult result = service.transferInternal(CurrentUser.id(jwt), request.sourceAccountNumber(),
                request.destinationAccountNumber(), Money.toKobo(request.amount()), request.narration(),
                request.pin(), checkKey(idempotencyKey));
        return respond(result, HttpStatus.CREATED);
    }

    @PostMapping("/transfers/external")
    @Operation(summary = "Pay out to a bank account (UBA, FCMB and others)",
            description = "The money is debited first and the status is PENDING. It becomes SUCCESSFUL, or FAILED with a refund. "
                    + "202 when accepted.")
    public ResponseEntity<TransferResponse> external(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A unique value per transfer, 8 to 100 characters.", example = "demo-010")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExternalTransferRequest request) {
        TransferResult result = service.transferExternal(CurrentUser.id(jwt), request.sourceAccountNumber(),
                request.bankCode(), request.destinationAccountNumber(), Money.toKobo(request.amount()),
                request.narration(), request.pin(), checkKey(idempotencyKey));
        return respond(result, HttpStatus.ACCEPTED);
    }

    @GetMapping("/transfers/{reference}")
    @Operation(summary = "One transfer", description = "Visible to the sender, and to the receiver of an internal transfer.")
    public TransferResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return TransferResponse.from(service.getVisible(CurrentUser.id(jwt), reference));
    }

    @PostMapping("/transfers/{reference}/verify")
    @Operation(summary = "Ask the bank network what happened to a pending payout")
    public TransferResponse verify(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return TransferResponse.from(service.verify(CurrentUser.id(jwt), reference));
    }

    @GetMapping("/accounts/{accountNumber}/transfers")
    @Operation(summary = "History for one of my accounts, newest first")
    public List<TransferResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.history(CurrentUser.id(jwt), accountNumber, page, size)
                .map(TransferResponse::from).getContent();
    }

    private static String checkKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (!KEY.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8 to 100 characters: letters, digits, dash, underscore, dot or colon.");
        }
        return trimmed;
    }

    private static ResponseEntity<TransferResponse> respond(TransferResult result, HttpStatus createdStatus) {
        TransferResponse body = TransferResponse.from(result.transfer());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
        }
        return ResponseEntity.status(createdStatus).body(body);
    }
}
