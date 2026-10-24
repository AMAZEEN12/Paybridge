package com.academy.paybridge.account.web;

import com.academy.paybridge.account.domain.FundingDetails;
import com.academy.paybridge.account.service.FundingDetailsService;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.shared.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "3. Accounts")
public class FundingDetailsController {

    public record FundingDetailsResponse(String currency, String accountName, String bankName, String bankCountry,
                                         String accountNumber, String routingLabel, String routingValue,
                                         String iban, String swiftBic, String reference, boolean simulated,
                                         String note) {
    }

    private final FundingDetailsService service;
    private final CustomerApi customers;

    public FundingDetailsController(FundingDetailsService service, CustomerApi customers) {
        this.service = service;
        this.customers = customers;
    }

    @GetMapping("/{currency}/funding-details")
    @Operation(summary = "Account details for receiving money into one of my wallets",
            description = "Issued the first time they are needed and the same every time after. In this sandbox the "
                    + "details are simulated: they are not a real bank account.")
    public FundingDetailsResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String currency) {
        Long customerId = CurrentUser.id(jwt);
        FundingDetails d = service.forWallet(customerId, currency);
        String code = currency.trim().toUpperCase();
        return new FundingDetailsResponse(code, customers.get(customerId).fullName(), d.getBankName(),
                d.getBankCountry(), d.getAccountNumber(), d.getRoutingLabel(), d.getRoutingValue(), d.getIban(),
                d.getSwiftBic(), d.getReference(), d.isSimulated(),
                "Sandbox details. This is not a real bank account and real money cannot be sent here.");
    }
}