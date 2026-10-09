package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.service.CustomerService;
import com.academy.paybridge.customer.web.AuthDtos.SetPinRequest;
import com.academy.paybridge.shared.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me")
@Tag(name = "2. My profile and PIN")
public class CustomerController {

    private final CustomerApi customerApi;
    private final CustomerService customerService;

    public CustomerController(CustomerApi customerApi, CustomerService customerService) {
        this.customerApi = customerApi;
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "Who am I?")
    public CustomerView me(@AuthenticationPrincipal Jwt jwt) {
        return customerApi.get(CurrentUser.id(jwt));
    }

    @PostMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set or change my 4-digit transaction PIN",
            description = "Every transfer asks for this PIN. If you already have one, send it as currentPin.")
    public void setPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetPinRequest request) {
        Long id = CurrentUser.id(jwt);
        if (customerApi.get(id).pinSet()) {
            customerApi.verifyPin(id, request.currentPin());   // counted and locked in its own transaction
        }
        customerService.setPin(id, request.pin());
    }
}
