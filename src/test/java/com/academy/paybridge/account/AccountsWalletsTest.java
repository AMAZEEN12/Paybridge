package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** No @Transactional on purpose, like PayBridgeFlowTest: these tests need real commits. */
@SpringBootTest
@ActiveProfiles("test")
class AccountWalletsTest {

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

    private Map<String, Object> walletRow(String accountNumber) {
        return jdbc.queryForMap(
                "select w.currency, w.balance_minor, w.is_home, w.status from wallets w"
                        + " join accounts a on a.id = w.account_id where a.account_number = ?", accountNumber);
    }

    @Test
    void openingAnAccountCreatesExactlyOneNgnHomeWallet() {
        AccountView opened = accountService.open(987_654_321L);

        Integer walletCount = jdbc.queryForObject(
                "select count(*) from wallets w join accounts a on a.id = w.account_id where a.account_number = ?",
                Integer.class, opened.accountNumber());
        assertThat(walletCount).isEqualTo(1);
        Map<String, Object> row = walletRow(opened.accountNumber());
        assertThat(row.get("currency")).isEqualTo("NGN");
        assertThat(row.get("is_home")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("balance_minor")).longValue()).isZero();
        assertThat(opened.balanceKobo()).isZero();
    }

    @Test
    void systemAccountsEachHaveOneNgnWallet() {
        for (String number : new String[]{"9000000001", "9000000002", "9000000003"}) {
            assertThat(walletRow(number).get("currency")).isEqualTo("NGN");
        }
    }

    @Test
    void fundingMovesTheNgnWalletAndTheDerivedBalanceFollows() {
        AccountView opened = accountService.open(987_654_322L);

        AccountView funded = accountService.devFund(opened.accountNumber(), 987_654_322L, 5_000L);

        assertThat(funded.balanceKobo()).isEqualTo(5_000L);
        assertThat(((Number) walletRow(opened.accountNumber()).get("balance_minor")).longValue()).isEqualTo(5_000L);
        assertThat(accountRepository.findByAccountNumber(opened.accountNumber()).orElseThrow().getBalanceKobo())
                .isEqualTo(5_000L);
    }
}