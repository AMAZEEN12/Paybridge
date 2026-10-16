package com.academy.paybridge.compliance.api;

/** The answer to "may money go from this country to that one?" (R-COR). {@code reason} is for logs and messages. */
public record CorridorResult(boolean allowed, String reason) {

    public static CorridorResult allow(String reason) {
        return new CorridorResult(true, reason);
    }

    public static CorridorResult refuse(String reason) {
        return new CorridorResult(false, reason);
    }
}