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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** No @Transactional on purpose: these tests need real commits. */
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
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @Test
    void fundingWritesAnNgnWalletKeyAndReconciliationStaysClean() {
        AccountView opened = accountService.open(987_654_323L);
        accountService.devFund(opened.accountNumber(), 987_654_323L, 7_000L);

        Map<String, Object> line = jdbc.queryForMap(
                "select currency, amount_minor, direction from ledger_entries where account_number = ?",
                opened.accountNumber() + ":NGN");
        assertThat(line.get("currency")).isEqualTo("NGN");
        assertThat(((Number) line.get("amount_minor")).longValue()).isEqualTo(7_000L);
        assertThat(line.get("direction")).isEqualTo("CREDIT");

        Integer plainKeys = jdbc.queryForObject(
                "select count(*) from ledger_entries where account_number not like 'EXT:%'"
                        + " and account_number not like '%:NGN'", Integer.class);
        assertThat(plainKeys).isZero();                  // every real-account line ends in :NGN

        assertThat(reconciliation.run().problems()).isEmpty();
    }
}