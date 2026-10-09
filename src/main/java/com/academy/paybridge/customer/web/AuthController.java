package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.customer.web.AuthDtos.LoginRequest;
import com.academy.paybridge.customer.web.AuthDtos.LoginResponse;
import com.academy.paybridge.customer.web.AuthDtos.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Sign up and log in")
@SecurityRequirements
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer account")
    public CustomerView register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.fullName(), request.email(), request.password());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive a token",
            description = "Copy the token, click Authorize at the top, and paste it. It expires after 30 minutes.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }
}
