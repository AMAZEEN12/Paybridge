package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.account.service.WalletQueryService;
import com.academy.paybridge.account.service.WalletService;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "3. Accounts")
public class WalletController {

    public record WalletResponse(String accountNumber, String currency, String name, BigDecimal balance,
                                 String display, String status, boolean home) {
    }

    public record SwapRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") String currency,
            @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") String parkCurrency) {
    }

    private final WalletQueryService query;
    private final WalletService walletService;
    private final AccountService accounts;
    private final CurrencyRegistry currencies;

    public WalletController(WalletQueryService query, WalletService walletService, AccountService accounts,
                            CurrencyRegistry currencies) {
        this.query = query;
        this.walletService = walletService;
        this.accounts = accounts;
        this.currencies = currencies;
    }

    @GetMapping
    @Operation(summary = "List my wallets", description = "Every wallet of my account with its balance. Read-only.")
    public List<WalletResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return query.forCustomer(CurrentUser.id(jwt)).stream().map(this::toResponse).toList();
    }

    @PostMapping("/swap")
    @Operation(summary = "Activate a currency, optionally parking another wallet",
            description = "Up to 4 wallets can be active. When all 4 are active, parkCurrency says which non-home wallet "
                    + "to park. A parked wallet keeps its balance and can still receive money, but cannot send.")
    public List<WalletResponse> swap(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SwapRequest request) {
        Long customerId = CurrentUser.id(jwt);
        // Looked up in its own short transaction, so the swap itself starts by taking the account lock.
        AccountView account = accounts.existingFor(customerId);
        return walletService.swap(customerId, account.accountNumber(), request.currency(), request.parkCurrency())
                .stream().map(this::toResponse).toList();
    }

    private WalletResponse toResponse(WalletView w) {
        return currencies.find(w.currency())
                .map(info -> {
                    CurrencyAmount amount = CurrencyAmount.of(w.balanceMinor(), info);
                    return new WalletResponse(w.accountNumber(), w.currency(), info.name(), amount.toDecimal(),
                            amount.format(), w.status(), w.home());
                })
                .orElseGet(() -> new WalletResponse(w.accountNumber(), w.currency(), w.currency(),
                        BigDecimal.valueOf(w.balanceMinor(), 2), w.balanceMinor() + " " + w.currency(),
                        w.status(), w.home()));
    }
}