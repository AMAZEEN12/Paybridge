package com.academy.paybridge.account;

import com.academy.paybridge.account.service.WalletRules;
import com.academy.paybridge.account.service.WalletRules.SwapPlan;
import com.academy.paybridge.account.service.WalletRules.WalletState;
import com.academy.paybridge.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletRulesTest {

    private static WalletState w(String currency, boolean active, boolean home) {
        return new WalletState(currency, active, home);
    }

    /** NGN (home), USD, EUR, GBP, all active: every slot used. */
    private static List<WalletState> full() {
        return List.of(w("NGN", true, true), w("USD", true, false), w("EUR", true, false), w("GBP", true, false));
    }

    /** USD (home), EUR, GBP: one free slot. */
    private static List<WalletState> threeWallets() {
        return List.of(w("USD", true, true), w("EUR", true, false), w("GBP", true, false));
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void aFreeSlotActivatesWithoutParking() {
        SwapPlan plan = WalletRules.planSwap(threeWallets(), "JPY", null);

        assertThat(plan.activate()).isEqualTo("JPY");
        assertThat(plan.reactivate()).isFalse();
        assertThat(plan.park()).isNull();
    }

    @Test
    void aFullAccountNeedsAWalletToPark() {
        assertCode(() -> WalletRules.planSwap(full(), "JPY", null), "PARK_REQUIRED");
    }

    @Test
    void parkingOneWalletFreesTheSlot() {
        SwapPlan plan = WalletRules.planSwap(full(), "JPY", "EUR");

        assertThat(plan.activate()).isEqualTo("JPY");
        assertThat(plan.park()).isEqualTo("EUR");
    }

    @Test
    void aParkedWalletIsReactivatedNotDuplicated() {
        List<WalletState> wallets = List.of(w("NGN", true, true), w("USD", true, false), w("GBP", true, false),
                w("EUR", false, false));

        SwapPlan plan = WalletRules.planSwap(wallets, "EUR", null);

        assertThat(plan.reactivate()).isTrue();
        assertThat(plan.park()).isNull();
    }

    @Test
    void reactivatingWhenFullStillNeedsAWalletToPark() {
        List<WalletState> wallets = List.of(w("NGN", true, true), w("USD", true, false), w("GBP", true, false),
                w("JPY", true, false), w("EUR", false, false));

        assertCode(() -> WalletRules.planSwap(wallets, "EUR", null), "PARK_REQUIRED");
        SwapPlan plan = WalletRules.planSwap(wallets, "EUR", "JPY");
        assertThat(plan.reactivate()).isTrue();
        assertThat(plan.park()).isEqualTo("JPY");
    }

    @Test
    void theHomeWalletCanNeverBeParked() {
        assertCode(() -> WalletRules.planSwap(full(), "JPY", "NGN"), "WALLET_HOME_FIXED");
        assertCode(() -> WalletRules.planSwap(threeWallets(), "JPY", "USD"), "WALLET_HOME_FIXED");
    }

    @Test
    void anAlreadyActiveCurrencyIsRefused() {
        assertCode(() -> WalletRules.planSwap(full(), "USD", null), "WALLET_ALREADY_ACTIVE");
        assertCode(() -> WalletRules.planSwap(full(), "NGN", "EUR"), "WALLET_ALREADY_ACTIVE");
    }

    @Test
    void onlyAnActiveWalletCanBeParked() {
        List<WalletState> wallets = List.of(w("NGN", true, true), w("USD", true, false), w("GBP", true, false),
                w("JPY", true, false), w("EUR", false, false));

        assertCode(() -> WalletRules.planSwap(wallets, "CHF", "EUR"), "PARK_INVALID");      // already parked
        assertCode(() -> WalletRules.planSwap(wallets, "CHF", "AUD"), "PARK_INVALID");      // does not exist
    }

    @Test
    void parkingIsRefusedWhenASlotIsFree() {
        assertCode(() -> WalletRules.planSwap(threeWallets(), "JPY", "EUR"), "PARK_NOT_NEEDED");
    }
}