package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.BatchView;
import com.academy.paybridge.settlement.api.RunSummary;
import com.academy.paybridge.settlement.service.SettlementAdminKey;
import com.academy.paybridge.settlement.service.SettlementBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Operators only (X-Admin-Key): see a UTC day's batches, or run a finished day that has none. */
@RestController
@RequestMapping("/api/v1/admin/settlement/batches")
@Tag(name = "10. Operations")
public class SettlementBatchController {

    private final SettlementAdminKey adminKey;
    private final SettlementBatchService service;

    public SettlementBatchController(SettlementAdminKey adminKey, SettlementBatchService service) {
        this.adminKey = adminKey;
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "The batches for one UTC day (operators only)")
    public List<BatchView> list(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKey.require(key);
        return service.list(date);
    }

    @PostMapping("/run")
    @Operation(summary = "Run a finished UTC day that has no batch yet (operators only)",
            description = "A day that was already run, or is not finished, is refused. A closed batch is never changed.")
    public RunSummary run(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                          @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminKey.require(key);
        return service.runManually(date);
    }
}