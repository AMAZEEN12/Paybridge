package com.academy.paybridge.remittance.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The partner's rate. {@code rate} is destination units per one source unit. {@code providerRef} and
 * {@code expiresAt} are filled only when the partner offers a rate id (PROVIDER_LOCK mode); otherwise null.
 */
public record ProviderRate(BigDecimal rate, String providerRef, Instant expiresAt) {
}