package com.academy.paybridge.customer.api;

import java.time.Instant;

public record CustomerView(Long id, String fullName, String email, boolean pinSet, Instant createdAt) {

    public CustomerView(Long id, String fullName, String email, boolean pinSet) {
        this(id, fullName, email, pinSet, null);
    }
}
