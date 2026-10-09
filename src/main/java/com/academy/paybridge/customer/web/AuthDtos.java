package com.academy.paybridge.customer.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the customer endpoints. Secrets are masked in toString(). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds) {
        @Override
        public String toString() {
            return "LoginResponse[tokenType=" + tokenType + "]";
        }
    }

    public record SetPinRequest(
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be exactly 4 digits") String pin,
            String currentPin) {
        @Override
        public String toString() {
            return "SetPinRequest[***]";
        }
    }
}
