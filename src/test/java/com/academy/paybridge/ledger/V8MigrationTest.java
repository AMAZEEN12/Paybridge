package com.academy.paybridge.ledger;

import com.academy.paybridge.support.TestDb;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V8MigrationTest {

    private static final String SCHEMA = "v8_migration_test";
    private static final List<String> RESET_TABLES = List.of(
            "customers", "accounts", "transfers", "ledger_entries", "audit_log",
            "notifications", "fraud_flags", "blocked_destinations");

    private static String url;
    private static String user;
    private static String password;

    private SingleConnectionDataSource connection;
    private JdbcTemplate jdbc;

    /** The one Flyway instance of this test class; its connection stays open until the JVM exits. */
    private Flyway flyway;

    @BeforeAll
    static void requireDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run the migration tests.");
        url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/paybridge_test1");
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
        try {
            if (jdbc != null) {
                jdbc.execute("set search_path to public");
                jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
            }
        } finally {
            if (connection != null) {
                connection.destroy();
            }
        }
    }

    /** Created on first use; migrate() is idempotent, so a test may call it more than once. */
    private Flyway flyway() {
        if (flyway == null) {
            flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .locations("classpath:db/migration")
                    .target("8")
                    .load();
        }
        return flyway;
    }

    private void migrate() {
        flyway().migrate();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = ? and table_name = ?",
                String.class, SCHEMA, table);
    }

    @Test
    void resetRunsOnAnEmptyDatabase() {
        migrate();

        for (String table : RESET_TABLES) {
            if (!table.equals("accounts")) {
                assertThat(count(table)).as(table).isZero();
            }
        }
        assertThat(jdbc.queryForList("select account_number from accounts order by account_number", String.class))
                .containsExactly("9000000001", "9000000002", "9000000003");
        assertThat(jdbc.queryForObject(
                "select count(*) from accounts where account_type = 'SYSTEM' and customer_id = 0 and balance_kobo = 0",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void resetClearsOldStyleDataAndRestartsIds() {
        migrate();   // the one-time V8 reset: every table starts empty and the ids restart

        for (String table : RESET_TABLES) {
            if (!table.equals("accounts")) {
                assertThat(count(table)).as(table).isZero();
            }
        }
        assertThat(jdbc.queryForList("select account_number from accounts", String.class))
                .containsExactlyInAnyOrder("9000000001", "9000000002", "9000000003");
        Long firstCustomerId = jdbc.queryForObject(
                "insert into customers (full_name, email, password_hash) values ('Bola', 'bola@example.test', 'h') returning id",
                Long.class);
        assertThat(firstCustomerId).isEqualTo(1L);   // identities were restarted
    }

    @Test
    void ledgerColumnsAreRenamedAndCurrencyIsRequired() {
        migrate();

        assertThat(columns("ledger_entries")).contains("amount_minor", "currency").doesNotContain("amount_kobo");
        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_entries (journal_key, account_number, direction, amount_minor, entry_type)"
                        + " values ('J2', '1000000001', 'CREDIT', 1, 'T')"))
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_entries (journal_key, account_number, direction, amount_minor, currency, entry_type)"
                        + " values ('J3', '1000000001', 'CREDIT', 0, 'NGN', 'T')"))
                .hasMessageContaining("ck_ledger_amount");
    }

    @Test
    void truncateWorksWhileUpdateAndDeleteAreStillRefused() {
        migrate();
        jdbc.update("insert into ledger_entries (journal_key, account_number, direction, amount_minor, currency, entry_type)"
                + " values ('J1', '1000000001', 'CREDIT', 100, 'NGN', 'T')");
        jdbc.update("insert into audit_log (actor, action) values ('test', 'TEST')");

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set entry_type = 'X'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'HACKED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log")).hasMessageContaining("append-only");
        assertThat(count("ledger_entries")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);

        // This is exactly what V8 relies on: TRUNCATE does not fire the row-level triggers.
        jdbc.execute("truncate table ledger_entries, audit_log restart identity");
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("audit_log")).isZero();
    }
}