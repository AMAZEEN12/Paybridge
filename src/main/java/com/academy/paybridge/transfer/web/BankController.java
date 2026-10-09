package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.gateway.GatewayRejectedException;
import com.academy.paybridge.transfer.gateway.GatewayUnavailableException;
import com.academy.paybridge.transfer.gateway.ResolvedAccount;
import com.academy.paybridge.transfer.gateway.TransferGateway;
import com.academy.paybridge.transfer.service.BankDirectory;
import com.academy.paybridge.transfer.service.ResolveRateLimiter;
import com.academy.paybridge.transfer.web.TransferDtos.BankResponse;
import com.academy.paybridge.transfer.web.TransferDtos.ResolveRequest;
import com.academy.paybridge.transfer.web.TransferDtos.ResolveResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/banks")
@Tag(name = "5. Banks and name check")
public class BankController {

    private final BankDirectory banks;
    private final TransferGateway gateway;
    private final ResolveRateLimiter limiter;
    private final AuditService audit;

    public BankController(BankDirectory banks, TransferGateway gateway, ResolveRateLimiter limiter, AuditService audit) {
        this.banks = banks;
        this.gateway = gateway;
        this.limiter = limiter;
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Banks you can pay out to", description = "Pick the code from here. Never type one by hand.")
    public List<BankResponse> list() {
        return banks.all().stream().map(b -> new BankResponse(b.name(), b.code())).toList();
    }

    @PostMapping("/resolve")
    @Operation(summary = "Who owns this bank account? (name enquiry)",
            description = "Check the name BEFORE you send. Limited to a few lookups per minute.")
    public ResolveResponse resolve(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ResolveRequest request) {
        Long customerId = CurrentUser.id(jwt);
        limiter.check(customerId);
        if (banks.find(request.bankCode()).isEmpty()) {
            throw ApiException.unprocessable("UNKNOWN_BANK", "That bank is not supported.");
        }
        audit.recordIndependently("customer:" + customerId, "NAME_ENQUIRY", null,
                request.bankCode() + " " + Money.maskAccount(request.accountNumber()));
        try {
            ResolvedAccount resolved = gateway.resolveAccount(request.accountNumber(), request.bankCode());
            return new ResolveResponse(resolved.accountName());
        } catch (GatewayRejectedException e) {
            throw ApiException.unprocessable("ACCOUNT_NOT_RESOLVED",
                    "We could not find that bank account. Check the bank and account number.");
        } catch (GatewayUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "We could not reach the bank network. Please try again.");
        }
    }
}
