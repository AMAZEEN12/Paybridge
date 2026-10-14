package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.account.service.WalletQueryService;
import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.shared.money.CurrencyAmount;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final WalletQueryService wallets;
    private final CurrencyRegistry currencies;

    public WalletController(WalletQueryService wallets, CurrencyRegistry currencies) {
        this.wallets = wallets;
        this.currencies = currencies;
    }

    @GetMapping
    @Operation(summary = "List my wallets",
            description = "Every wallet of my account with its balance. Read-only.")
    public List<WalletResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return wallets.forCustomer(CurrentUser.id(jwt)).stream().map(this::toResponse).toList();
    }

    private WalletResponse toResponse(WalletView w) {
        return currencies.find(w.currency())
                .map(info -> {
                    CurrencyAmount amount = CurrencyAmount.of(w.balanceMinor(), info);
                    return new WalletResponse(w.accountNumber(), w.currency(), info.name(), amount.toDecimal(),
                            amount.format(), w.status(), w.home());
                })
                // Unknown currency row (should not happen): show the plain number and code.
                .orElseGet(() -> new WalletResponse(w.accountNumber(), w.currency(), w.currency(),
                        BigDecimal.valueOf(w.balanceMinor(), 2), w.balanceMinor() + " " + w.currency(),
                        w.status(), w.home()));
    }
}