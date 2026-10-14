package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.account.web.AccountDtos.AccountResponse;
import com.academy.paybridge.account.web.AccountDtos.FundRequest;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyRegistry;
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

import java.util.Locale;

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
    private final CurrencyRegistry currencies;

    public DevFundingController(AccountService accounts, CurrencyRegistry currencies) {
        this.accounts = accounts;
        this.currencies = currencies;
    }

    @PostMapping("/{accountNumber}/fund")
    @Operation(summary = "Add test money to one of my wallets (not available in production)",
            description = "currency is optional and defaults to NGN. A parked wallet can be funded too.")
    public AccountResponse fund(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                @Valid @RequestBody FundRequest request) {
        String code = request.currency() == null || request.currency().isBlank()
                ? "NGN" : request.currency().trim().toUpperCase(Locale.ROOT);
        CurrencyAmount amount = currencies.parse(request.amount(), code);   // checks the currency and its decimals
        Long customerId = CurrentUser.id(jwt);
        AccountView view = "NGN".equals(code)
                ? accounts.devFund(accountNumber, customerId, amount.minor())
                : accounts.devFund(accountNumber, customerId, code, amount.minor());
        return AccountResponse.from(view);
    }
}