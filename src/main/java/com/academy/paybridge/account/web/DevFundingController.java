package com.academy.paybridge.account.web;

import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.account.web.AccountDtos.AccountResponse;
import com.academy.paybridge.account.web.AccountDtos.FundRequest;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY. This creates money from nowhere so you can demo transfers before real funding
 * (direct debit) exists. It is switched off with PAYBRIDGE_DEV_FUNDING_ENABLED=false.
 */
@RestController
@ConditionalOnProperty(name = "paybridge.dev-funding-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/dev/accounts")
@Tag(name = "9. Development only")
public class DevFundingController {

    private final AccountService accounts;

    public DevFundingController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/{accountNumber}/fund")
    @Operation(summary = "Add test money to my account (not available in production)")
    public AccountResponse fund(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                @Valid @RequestBody FundRequest request) {
        return AccountResponse.from(
                accounts.devFund(accountNumber, CurrentUser.id(jwt), Money.toKobo(request.amount())));
    }
}
