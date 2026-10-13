package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** No @Transactional on purpose, like PayBridgeFlowTest: these tests need real commits. */
@SpringBootTest
@ActiveProfiles("test")
class AccountsWalletsTest {

    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    private static long newCustomerId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    }

    private List<Map<String, Object>> walletRows(String accountNumber) {
        return jdbc.queryForList(
                "select w.currency, w.balance_minor, w.is_home, w.status from wallets w"
                        + " join accounts a on a.id = w.account_id where a.account_number = ? order by w.currency",
                accountNumber);
    }

    private List<String> homeCurrencies(List<Map<String, Object>> rows) {
        return rows.stream().filter(r -> Boolean.TRUE.equals(r.get("is_home")))
                .map(r -> (String) r.get("currency")).toList();
    }

    @Test
    void aNigerianHomeAccountGetsNgnPlusUsdEurGbp() {
        AccountView opened = accountService.openHomeAccount(newCustomerId(), "NGN");

        List<Map<String, Object>> rows = walletRows(opened.accountNumber());
        assertThat(rows).extracting(r -> r.get("currency")).containsExactlyInAnyOrder("NGN", "USD", "EUR", "GBP");
        assertThat(homeCurrencies(rows)).containsExactly("NGN");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) r.get("balance_minor")).longValue()).isZero();
        });
        assertThat(opened.balanceKobo()).isZero();
    }

    @Test
    void aUsdHomeAccountGetsThreeWalletsAndNoNgnWallet() {
        AccountView opened = accountService.openHomeAccount(newCustomerId(), "USD");

        List<Map<String, Object>> rows = walletRows(opened.accountNumber());
        assertThat(rows).extracting(r -> r.get("currency")).containsExactlyInAnyOrder("USD", "EUR", "GBP");
        assertThat(homeCurrencies(rows)).containsExactly("USD");
    }

    @Test
    void aCustomerCannotHaveASecondAccount() {
        long customerId = newCustomerId();
        accountService.openHomeAccount(customerId, "NGN");

        assertThatThrownBy(() -> accountService.openHomeAccount(customerId, "NGN"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_EXISTS"));
    }

    @Test
    void systemAccountsEachHaveOneNgnWallet() {
        for (String number : new String[]{"9000000001", "9000000002", "9000000003"}) {
            List<Map<String, Object>> rows = walletRows(number);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("currency")).isEqualTo("NGN");
        }
    }

    @Test
    void fundingMovesTheNgnWalletAndTheDerivedBalanceFollows() {
        long customerId = newCustomerId();
        AccountView opened = accountService.openHomeAccount(customerId, "NGN");

        AccountView funded = accountService.devFund(opened.accountNumber(), customerId, 5_000L);

        assertThat(funded.balanceKobo()).isEqualTo(5_000L);
        Map<String, Object> ngn = walletRows(opened.accountNumber()).stream()
                .filter(r -> "NGN".equals(r.get("currency"))).findFirst().orElseThrow();
        assertThat(((Number) ngn.get("balance_minor")).longValue()).isEqualTo(5_000L);
        assertThat(accountRepository.findByAccountNumber(opened.accountNumber()).orElseThrow().getBalanceKobo())
                .isEqualTo(5_000L);
    }
}