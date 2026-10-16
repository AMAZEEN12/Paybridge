package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.ExceptionView;
import com.academy.paybridge.settlement.service.SettlementAdminKey;
import com.academy.paybridge.settlement.service.SettlementMatchingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Operators only (X-Admin-Key): list open exceptions and resolve one with a required note (R-SET-10, R-SET-12). */
@RestController
@RequestMapping("/api/v1/admin/settlement/exceptions")
@Tag(name = "10. Operations")
public class SettlementExceptionController {

    private final SettlementAdminKey adminKey;
    private final SettlementMatchingService matching;

    public SettlementExceptionController(SettlementAdminKey adminKey, SettlementMatchingService matching) {
        this.adminKey = adminKey;
        this.matching = matching;
    }

    public record ResolveRequest(@NotBlank @Size(max = 500) String note) {
    }

    @GetMapping
    @Operation(summary = "Open settlement exceptions (operators only)",
            description = "Pass includeResolved=true to see resolved ones too.")
    public List<ExceptionView> list(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                    @RequestParam(value = "includeResolved", defaultValue = "false") boolean includeResolved) {
        adminKey.require(key);
        return matching.list(includeResolved);
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve an exception with a note (operators only)",
            description = "The note is stored on the exception and written to the audit log. Nothing is edited or deleted.")
    public ExceptionView resolve(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                 @AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long id,
                                 @Valid @RequestBody ResolveRequest request) {
        adminKey.require(key);
        return matching.resolve(id, "operator:" + jwt.getSubject(), request.note());
    }
}