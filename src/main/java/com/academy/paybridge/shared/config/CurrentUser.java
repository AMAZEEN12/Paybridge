package com.academy.paybridge.shared.config;

import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    /** The customer id is the JWT subject. It always comes from the token, never from the URL or body. */
    public static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
