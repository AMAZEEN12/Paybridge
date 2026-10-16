package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.service.PayoutDestinationsService;
import com.academy.paybridge.remittance.service.PayoutVerificationService;
import com.academy.paybridge.remittance.web.PayoutDtos.CountryDto;
import com.academy.paybridge.remittance.web.PayoutDtos.DestinationsResponse;
import com.academy.paybridge.remittance.web.PayoutDtos.VerifyAccountRequest;
import com.academy.paybridge.remittance.web.PayoutDtos.VerifyAccountResponse;
import com.academy.paybridge.shared.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payouts")
@Tag(name = "5. Payouts")
public class PayoutController {

    private final PayoutDestinationsService destinations;
    private final PayoutVerificationService verification;

    public PayoutController(PayoutDestinationsService destinations, PayoutVerificationService verification) {
        this.destinations = destinations;
        this.verification = verification;
    }

    @GetMapping("/destinations")
    @Operation(summary = "Countries a payout can go to, their currencies, the bank details each needs, and the purposes")
    public DestinationsResponse destinations() {
        return new DestinationsResponse(
                destinations.countries().stream().map(CountryDto::from).toList(),
                destinations.purposes());
    }

    @PostMapping("/verify-account")
    @Operation(summary = "Check whose bank account this is before asking for a payout quote",
            description = "Returns the name the partner reports and where it came from. A name that is not verified "
                    + "(test mode) is labelled as such. Rate limited, because it reveals who owns an account.")
    public VerifyAccountResponse verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyAccountRequest request) {
        return VerifyAccountResponse.from(verification.verify(CurrentUser.id(jwt),
                new PayoutVerificationService.VerifyRequest(request.country(), request.currency(), request.bankCode(),
                        request.accountNumber(), request.bankFields())));
    }
}