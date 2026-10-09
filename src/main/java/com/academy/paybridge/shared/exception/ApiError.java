package com.academy.paybridge.shared.exception;

import java.time.Instant;

public record ApiError(String code, String message, String requestId, Instant timestamp) {
}
