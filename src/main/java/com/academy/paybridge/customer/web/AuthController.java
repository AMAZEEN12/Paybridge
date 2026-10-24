package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.customer.service.RegistrationService;
import com.academy.paybridge.customer.web.AuthDtos.LoginRequest;
import com.academy.paybridge.customer.web.AuthDtos.LoginResponse;
import com.academy.paybridge.customer.web.AuthDtos.RefreshRequest;
import com.academy.paybridge.customer.web.AuthDtos.RegisterRequest;
import com.academy.paybridge.customer.web.AuthDtos.RegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RegistrationService registration;

    public AuthController(AuthService auth, RegistrationService registration) {
        this.auth = auth;
        this.registration = registration;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer and their account",
            description = "Your country is detected from your IP address. The account gets a wallet in your country's "
                    + "currency plus USD, EUR and GBP. Refused if PayBridge is not available in your country.")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        String header = registration.clientIpHeader();
        String headerValue = header.isEmpty() ? null : http.getHeader(header);
        RegistrationService.Result result = registration.register(request.fullName(), request.email(),
                request.password(), headerValue, http.getRemoteAddr());
        CustomerView c = result.customer();
        return new RegisterResponse(c.id(), c.fullName(), c.email(), c.pinSet(),
                result.country(), result.countryName(), result.homeCurrency());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive a token",
            description = "Copy the token, click Authorize at the top, and paste it. It expires after 30 minutes.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Obtain a fresh access token using a refresh token",
            description = "Keeps the customer logged in during long operations or background tasks without interruption.")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh token on sign out")
    public void logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null) {
            auth.logout(request.refreshToken());
        }
    }
}
