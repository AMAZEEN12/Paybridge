package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.remittance.service.PayoutConfirmationService;
import com.academy.paybridge.remittance.service.PayoutDestinationsService;
import com.academy.paybridge.remittance.service.PayoutQuoteService;
import com.academy.paybridge.remittance.service.PayoutVerificationService;
import com.academy.paybridge.remittance.web.PayoutDtos.ConfirmPayoutRequest;
import com.academy.paybridge.remittance.web.PayoutDtos.CountryDto;
import com.academy.paybridge.remittance.web.PayoutDtos.CreatePayoutQuoteRequest;
import com.academy.paybridge.remittance.web.PayoutDtos.DestinationsResponse;
import com.academy.paybridge.remittance.web.PayoutDtos.PayoutQuoteResponse;
import com.academy.paybridge.remittance.web.PayoutDtos.PayoutResponse;
import com.academy.paybridge.remittance.web.PayoutDtos.VerifyAccountRequest;
import com.academy.paybridge.remittance.web.PayoutDtos.VerifyAccountResponse;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/payouts")
@Tag(name = "5. Payouts")
public class PayoutController {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{8,100}");

    private final PayoutDestinationsService destinations;
    private final PayoutVerificationService verification;
    private final PayoutQuoteService payoutQuotes;
    private final PayoutConfirmationService confirmation;
    private final RemittanceRepository remittances;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public PayoutController(PayoutDestinationsService destinations, PayoutVerificationService verification,
                            PayoutQuoteService payoutQuotes, PayoutConfirmationService confirmation,
                            RemittanceRepository remittances, CurrencyRegistry currencies, Clock clock) {
        this.destinations = destinations;
        this.verification = verification;
        this.payoutQuotes = payoutQuotes;
        this.confirmation = confirmation;
        this.remittances = remittances;
        this.currencies = currencies;
        this.clock = clock;
    }

    @GetMapping("/destinations")
    @Operation(summary = "Countries a payout can go to, their currencies, required bank details and purposes")
    public DestinationsResponse destinations() {
        return new DestinationsResponse(destinations.countries().stream().map(CountryDto::from).toList(),
                destinations.purposes());
    }

    @PostMapping("/verify-account")
    @Operation(summary = "Check whose bank account this is before asking for a payout quote",
            description = "Returns the name the partner reports and where it came from. A name that is not verified "
                    + "(test mode) is labelled as such. Rate limited.")
    public VerifyAccountResponse verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyAccountRequest request) {
        return VerifyAccountResponse.from(verification.verify(CurrentUser.id(jwt),
                new PayoutVerificationService.VerifyRequest(request.country(), request.currency(), request.bankCode(),
                        request.accountNumber(), request.bankFields())));
    }

    @PostMapping("/quotes")
    @Operation(summary = "Quote a payout to a bank account. Changes nothing.",
            description = "Verifies the recipient, gets the partner's rate when a conversion is needed, and locks the "
                    + "destination, name, fee (1%, at least $1) and exact amount received for 30 minutes.")
    public PayoutQuoteResponse createQuote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePayoutQuoteRequest request) {
        var result = payoutQuotes.create(CurrentUser.id(jwt), new PayoutQuoteService.CreateRequest(
                request.sourceCurrency(), request.amount(), request.country(), request.bankCode(),
                request.accountNumber(), request.bankFields(), request.recipientType(), request.purpose()));
        return PayoutQuoteResponse.from(result, currencies, clock.instant());
    }

    @PostMapping({"", "/confirm"})
    @Operation(summary = "Confirm a payout quote with your PIN",
            description = "Debits you once, then sends the payout. 201 on success, 200 if this Idempotency-Key was "
                    + "already used for the same quote. The result starts PENDING and settles by webhook, verify or job.")
    public ResponseEntity<PayoutResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A unique value per payout, 8 to 100 characters.", example = "po-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConfirmPayoutRequest request) {
        var confirmed = confirmation.confirm(CurrentUser.id(jwt), request.quoteId(), request.pin(), checkKey(idempotencyKey));
        PayoutResponse body = PayoutResponse.from(confirmed.remittance(), currencies);
        if (confirmed.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Read one of your payouts")
    public PayoutResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        Long customerId = CurrentUser.id(jwt);
        return remittances.findByReference(reference)
                .filter(r -> r.getCustomerId().equals(customerId))          // another customer's payout looks missing
                .map(r -> PayoutResponse.from(r, currencies))
                .orElseThrow(() -> ApiException.notFound("PAYOUT_NOT_FOUND", "Payout not found."));
    }

    @GetMapping
    @Operation(summary = "List your payouts, newest first")
    public List<PayoutResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return remittances.findByCustomerIdOrderByCreatedAtDesc(CurrentUser.id(jwt), PageRequest.of(0, 50)).stream()
                .map(r -> PayoutResponse.from(r, currencies)).toList();
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