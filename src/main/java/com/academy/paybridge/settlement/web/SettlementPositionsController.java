package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.PositionReport;
import com.academy.paybridge.settlement.service.PositionService;
import com.academy.paybridge.settlement.service.SettlementAdminKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operators only: company-wide positions. A customer token is not enough; X-Admin-Key is also needed. */
@RestController
@RequestMapping("/api/v1/admin/settlement")
@Tag(name = "10. Operations")
public class SettlementPositionsController {

    private final SettlementAdminKey adminKey;
    private final PositionService positions;

    public SettlementPositionsController(SettlementAdminKey adminKey, PositionService positions) {
        this.adminKey = adminKey;
        this.positions = positions;
    }

    @GetMapping("/positions")
    @Operation(summary = "What each pool, fee, in-flight, paid-out and system account holds, per currency (operators only)",
            description = "Credits minus debits from the ledger, never stored. A position below its configured minimum is flagged.")
    public PositionReport positions(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        adminKey.require(key);
        return positions.report();
    }
}