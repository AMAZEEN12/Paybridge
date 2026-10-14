package com.academy.paybridge.customer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.customer.geo.ClientCountryResolver;
import com.academy.paybridge.customer.service.RegistrationService;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** No @Transactional on purpose: these tests need real commits. The geo service is never called. */
@SpringBootTest
@ActiveProfiles("test")
class SignUpCountriesTest {

    @MockitoBean
    ClientCountryResolver resolver;

    @Autowired
    RegistrationService registration;
    @Autowired
    AccountApi accountApi;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @BeforeEach
    void noHeader() {
        when(resolver.clientIpHeader()).thenReturn("");
    }

    private String newEmail() {
        return "signup-" + UUID.randomUUID() + "@example.com";
    }

    private RegistrationService.Result signUpFrom(String country, String email) {
        when(resolver.countryOf(any(), any())).thenReturn(country);
        return registration.register("Test User", email, "password123", null, "127.0.0.1");
    }

    private List<Map<String, Object>> wallets(String email) {
        return jdbc.queryForList(
                "select w.currency, w.is_home from wallets w join accounts a on a.id = w.account_id"
                        + " join customers c on c.id = a.customer_id where c.email = ?", email);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void aNigerianSignUpGetsNgnUsdEurGbpWithNgnAsHome() {
        String email = newEmail();

        RegistrationService.Result result = signUpFrom("NG", email);

        assertThat(result.country()).isEqualTo("NG");
        assertThat(result.homeCurrency()).isEqualTo("NGN");
        assertThat(jdbc.queryForObject("select country from customers where email = ?", String.class, email))
                .isEqualTo("NG");
        List<Map<String, Object>> rows = wallets(email);
        assertThat(rows).extracting(r -> r.get("currency")).containsExactlyInAnyOrder("NGN", "USD", "EUR", "GBP");
        assertThat(rows).filteredOn(r -> Boolean.TRUE.equals(r.get("is_home")))
                .extracting(r -> r.get("currency")).containsExactly("NGN");
    }

    @Test
    void aUsdHomeSignUpGetsThreeWalletsAndOneFreeSlot() {
        String email = newEmail();

        RegistrationService.Result result = signUpFrom("US", email);

        assertThat(result.homeCurrency()).isEqualTo("USD");
        List<Map<String, Object>> rows = wallets(email);
        assertThat(rows).extracting(r -> r.get("currency")).containsExactlyInAnyOrder("USD", "EUR", "GBP");
        assertThat(rows).hasSize(3);                                     // 4 are allowed, so one slot is free
        assertThat(rows).filteredOn(r -> Boolean.TRUE.equals(r.get("is_home")))
                .extracting(r -> r.get("currency")).containsExactly("USD");
    }

    @Test
    void aBlockedOrUnsupportedCountryCreatesNoCustomerAndNoAccount() {
        for (String country : new String[]{"IR", "KP", "SY", "CU", "ZZ"}) {
            int customers = count("customers");
            int accounts = count("accounts");
            String email = newEmail();

            assertThatThrownBy(() -> signUpFrom(country, email))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("COUNTRY_NOT_SUPPORTED"));

            assertThat(count("customers")).as(country + " customers").isEqualTo(customers);
            assertThat(count("accounts")).as(country + " accounts").isEqualTo(accounts);
        }
    }

    @Test
    void whenTheCountryCannotBeDetectedNothingIsCreated() {
        int customers = count("customers");
        int accounts = count("accounts");
        when(resolver.countryOf(any(), any())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COUNTRY_UNDETECTED", "no country"));

        assertThatThrownBy(() -> registration.register("Test User", newEmail(), "password123", null, "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("COUNTRY_UNDETECTED"));

        assertThat(count("customers")).isEqualTo(customers);
        assertThat(count("accounts")).isEqualTo(accounts);
    }

    @Test
    void everyWalletIsReportedToReconciliationSoNoneLooksUnknown() {
        String email = newEmail();
        signUpFrom("NG", email);
        String accountNumber = jdbc.queryForObject(
                "select a.account_number from accounts a join customers c on c.id = a.customer_id where c.email = ?",
                String.class, email);

        Map<String, Long> balances = accountApi.balances();

        assertThat(balances).containsKeys(accountNumber + ":NGN", accountNumber + ":USD",
                accountNumber + ":EUR", accountNumber + ":GBP");
        assertThat(reconciliation.run().problems()).isEmpty();
    }
}