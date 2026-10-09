package com.academy.paybridge.account.web;

import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.account.web.AccountDtos.AccountResponse;
import com.academy.paybridge.account.web.AccountDtos.PinRequest;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.shared.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "3. Accounts")
public class AccountController {

    private final AccountService accounts;
    private final CustomerApi customers;

    public AccountController(AccountService accounts, CustomerApi customers) {
        this.accounts = accounts;
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a new naira account with a zero balance")
    public AccountResponse open(@AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(accounts.open(CurrentUser.id(jwt)));
    }

    @GetMapping
    @Operation(summary = "List my accounts and balances")
    public List<AccountResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return accounts.listFor(CurrentUser.id(jwt)).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "One of my accounts", description = "Returns 404 for an account that is not yours.")
    public AccountResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber) {
        return AccountResponse.from(accounts.getOwned(accountNumber, CurrentUser.id(jwt)));
    }

    @PostMapping("/{accountNumber}/freeze")
    @Operation(summary = "Emergency brake: freeze my account",
            description = "A frozen account cannot send money, but can still receive. No PIN needed, so it is fast.")
    public AccountResponse freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber) {
        return AccountResponse.from(accounts.setFrozen(accountNumber, CurrentUser.id(jwt), true));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    @Operation(summary = "Unfreeze my account (needs my PIN)")
    public AccountResponse unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber,
                                    @Valid @RequestBody PinRequest request) {
        Long customerId = CurrentUser.id(jwt);
        accounts.getOwned(accountNumber, customerId);              // 404 first if it is not mine
        customers.verifyPin(customerId, request.pin());
        return AccountResponse.from(accounts.setFrozen(accountNumber, customerId, false));
    }
}
