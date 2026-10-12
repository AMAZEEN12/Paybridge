package com.academy.paybridge.account;

import com.academy.paybridge.support.TestDb;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WalletsV9MigrationTest {

    private static final String SCHEMA = "v9_migration_test";

    private static String url;
    private static String user;
    private static String password;

    private SingleConnectionDataSource connection;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run the migration tests.");
        url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/paybridge_test");
        user = System.getenv().getOrDefault("TEST_DB_USER", "postgres");
        password = System.getenv().getOrDefault("TEST_DB_PASSWORD", System.getenv().getOrDefault("DB_PASSWORD", ""));
    }

    @BeforeEach
    void freshSchema() {
        connection = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(connection);
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        jdbc.execute("create schema " + SCHEMA);
        jdbc.execute("set search_path to " + SCHEMA);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("set search_path to public");
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        connection.destroy();
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, user, password)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    @Test
    void everyAccountGetsAnNgnHomeWalletAndBalancesCarryOver() {
        migrateTo("8");
        jdbc.update("insert into accounts (account_number, customer_id, balance_kobo) values ('2000000001', 1, 5000000)");

        migrateTo("9");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select a.account_number, w.currency, w.balance_minor, w.is_home, w.status"
                        + " from wallets w join accounts a on a.id = w.account_id order by a.account_number");
        assertThat(rows).hasSize(4);                                   // 3 system accounts + 1 customer
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("currency")).isEqualTo("NGN");
            assertThat(r.get("is_home")).isEqualTo(true);
            assertThat(r.get("status")).isEqualTo("ACTIVE");
        });
        assertThat(rows.get(0).get("account_number")).isEqualTo("2000000001");
        assertThat(((Number) rows.get(0).get("balance_minor")).longValue()).isEqualTo(5_000_000L);
        assertThat(jdbc.queryForObject("select sum(balance_minor) from wallets where account_id in"
                + " (select id from accounts where account_type = 'SYSTEM')", Long.class)).isZero();
    }

    @Test
    void balanceColumnIsGoneAndWalletRulesAreEnforced() {
        migrateTo("9");

        assertThat(jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = ? and table_name = 'accounts'",
                String.class, SCHEMA)).doesNotContain("balance_kobo");
        assertThatThrownBy(() -> jdbc.update(
                "insert into wallets (account_id, currency) select id, 'NGN' from accounts limit 1"))
                .hasMessageContaining("uq_wallets_account_currency");
        assertThatThrownBy(() -> jdbc.update("update wallets set balance_minor = -1"))
                .hasMessageContaining("ck_wallets_balance");
        assertThatThrownBy(() -> jdbc.update("update wallets set status = 'X'"))
                .hasMessageContaining("ck_wallets_status");
    }
}