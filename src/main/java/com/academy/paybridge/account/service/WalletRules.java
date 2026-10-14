package com.academy.paybridge.account.service;

import com.academy.paybridge.shared.exception.ApiException;

import java.util.Collection;

/** The swap and wallet-cap rules (R-WAL-1, 4, 5). Plain Java: no database, no Spring. */
public final class WalletRules {

    public static final int MAX_ACTIVE_WALLETS = 4;

    public record WalletState(String currency, boolean active, boolean home) {
    }

    /** What a swap will do. park is null when nothing needs parking. */
    public record SwapPlan(String activate, boolean reactivate, String park) {
    }

    private WalletRules() {
    }

    public static SwapPlan planSwap(Collection<WalletState> wallets, String target, String parkCurrency) {
        WalletState existing = find(wallets, target);
        if (existing != null && existing.active()) {
            throw ApiException.conflict("WALLET_ALREADY_ACTIVE",
                    "You already have an active " + target + " wallet.");
        }

        long active = wallets.stream().filter(WalletState::active).count();
        boolean full = active >= MAX_ACTIVE_WALLETS;

        if (parkCurrency != null) {
            WalletState toPark = find(wallets, parkCurrency);
            if (toPark != null && toPark.home()) {
                throw ApiException.unprocessable("WALLET_HOME_FIXED", "Your home wallet cannot be parked.");
            }
            if (toPark == null || !toPark.active()) {
                throw ApiException.unprocessable("PARK_INVALID", "You can only park a wallet that is active.");
            }
            if (!full) {
                throw ApiException.unprocessable("PARK_NOT_NEEDED",
                        "You have a free wallet slot, so nothing needs to be parked.");
            }
            return new SwapPlan(target, existing != null, parkCurrency);
        }

        if (full) {
            throw ApiException.unprocessable("PARK_REQUIRED",
                    "All " + MAX_ACTIVE_WALLETS + " wallet slots are in use. Choose a wallet to park.");
        }
        return new SwapPlan(target, existing != null, null);
    }

    private static WalletState find(Collection<WalletState> wallets, String currency) {
        return wallets.stream().filter(w -> w.currency().equals(currency)).findFirst().orElse(null);
    }
}