package com.academy.paybridge.customer.api;

public record CustomerView(Long id, String fullName, String email, boolean pinSet) {
}
