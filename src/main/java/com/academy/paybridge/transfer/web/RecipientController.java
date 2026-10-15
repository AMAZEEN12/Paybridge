package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.config.CurrentUser;
import com.academy.paybridge.transfer.service.RecipientLookupService;
import com.academy.paybridge.transfer.service.RecipientLookupService.Recipient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recipients")
@Tag(name = "4. Transfers")
public class RecipientController {

    public record RecipientResponse(String accountNumber, String accountName, List<String> receivableCurrencies) {

        static RecipientResponse from(Recipient r) {
            return new RecipientResponse(r.accountNumber(), r.accountName(), r.receivableCurrencies());
        }
    }

    private final RecipientLookupService recipients;

    public RecipientController(RecipientLookupService recipients) {
        this.recipients = recipients;
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Who owns this PayBridge account, and which currencies can it receive?",
            description = "Check the name BEFORE you send. A parked wallet still counts as receivable. Shows no balances. "
                    + "Limited to a few lookups per minute.")
    public RecipientResponse lookup(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber) {
        return RecipientResponse.from(recipients.lookup(CurrentUser.id(jwt), accountNumber));
    }
}