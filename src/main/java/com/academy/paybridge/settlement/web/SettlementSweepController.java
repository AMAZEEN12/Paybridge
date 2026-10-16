package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.SweepReport;
import com.academy.paybridge.settlement.service.SettlementAdminKey;
import com.academy.paybridge.settlement.service.SweepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operators only (X-Admin-Key): sweep fee and tax accounts to revenue and tax payable (R-SET-11, S13). */
@RestController
@RequestMapping("/api/v1/admin/settlement/sweeps")
@Tag(name = "10. Operations")
public class SettlementSweepController {

    private final SettlementAdminKey adminKey;
    private final SweepService sweeps;

    public SettlementSweepController(SettlementAdminKey adminKey, SweepService sweeps) {
        this.adminKey = adminKey;
        this.sweeps = sweeps;
    }

    public record SweepRequest(@Pattern(regexp = "[A-Z]{3}", message = "Use a three-letter currency code, for example NGN.")
                               String currency) {
    }

    @PostMapping
    @Operation(summary = "Sweep fee and tax accounts to revenue and tax payable (operators only)",
            description = "Send {\"currency\":\"NGN\"} for one currency, or no body for all. One journal per source account. "
                    + "System wallets are debited in the same transaction. Zero balances are skipped.")
    public SweepReport sweep(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                             @AuthenticationPrincipal Jwt jwt,
                             @Valid @RequestBody(required = false) SweepRequest request) {
        adminKey.require(key);
        return sweeps.run("operator:" + jwt.getSubject(), request == null ? null : request.currency());
    }
}