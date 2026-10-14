package com.academy.paybridge.ledger;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@ActiveProfiles("test")
class LedgerWalletKeysTest {

    @Autowired
    AccountService accountService;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(
                TestDb.available(),
                "No test database. Create one to run these tests."
        );
    }

    @Test
    void fundingWritesAnNgnWalletKeyAndReconciliationStaysClean() {
        long customerId = uniqueCustomerId();

        jdbc.update("""
                INSERT INTO customers (
                    id,
                    full_name,
                    email,
                    password_hash,
                    country
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                customerId,
                "Ledger Test Customer",
                "ledger-test-" + UUID.randomUUID() + "@example.com",
                "test-only-password-hash",
                "NG"
        );

        AccountView opened =
                accountService.openHomeAccount(customerId, "NGN");

        accountService.devFund(
                opened.accountNumber(),
                customerId,
                7_000L
        );

        Map<String, Object> line = jdbc.queryForMap(
                """
                SELECT currency, amount_minor, direction
                FROM ledger_entries
                WHERE account_number = ?
                """,
                opened.accountNumber() + ":NGN"
        );

        assertThat(line.get("currency")).isEqualTo("NGN");
        assertThat(((Number) line.get("amount_minor")).longValue())
                .isEqualTo(7_000L);
        assertThat(line.get("direction")).isEqualTo("CREDIT");

        Integer plainKeys = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM ledger_entries
                WHERE account_number NOT LIKE 'EXT:%'
                  AND account_number NOT LIKE '%:NGN'
                """,
                Integer.class
        );

        assertThat(plainKeys).isZero();
        assertThat(reconciliation.run().problems()).isEmpty();
    }

    private long uniqueCustomerId() {
        long customerId;

        do {
            customerId = 900_000_000L
                    + (long) (Math.random() * 99_999_999L);
        } while (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customers WHERE id = ?)",
                Boolean.class,
                customerId
        )));

        return customerId;
    }
}
