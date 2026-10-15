package com.academy.paybridge.transfer;

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

/**
 * Plan 002-004, step A1: V14 turns the transfers kobo columns into minor-unit columns and adds a currency.
 * Each test builds a private schema, migrates to V13, optionally adds rows, then applies V14.
 */
class TransfersMinorMigrationTest {

    private static final String SCHEMA = "v14_migration_test";

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

    private List<String> columns() {
        return jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = ? and table_name = 'transfers'",
                String.class, SCHEMA);
    }

    private void insertOldStyle(String reference, String type, long amount, long fee, long vat, long stamp, long total) {
        jdbc.update("insert into transfers (reference, type, status, source_account_number, destination_account_number,"
                        + " amount_kobo, fee_kobo, vat_kobo, stamp_duty_kobo, total_debit_kobo, idempotency_key, request_hash)"
                        + " values (?, ?, 'SUCCESSFUL', '1000000001', '1000000002', ?, ?, ?, ?, ?, ?, 'h')",
                reference, type, amount, fee, vat, stamp, total, "key-" + reference);
    }

    @Test
    void columnsAreRenamedAndNewOnesAdded_onAnEmptyDatabase() {
        migrateTo("13");
        migrateTo("14");

        assertThat(columns())
                .contains("amount_minor", "fee_minor", "vat_minor", "stamp_duty_minor", "total_debit_minor",
                        "currency", "target_currency", "target_amount_minor", "quote_id")
                .doesNotContain("amount_kobo", "fee_kobo", "vat_kobo", "stamp_duty_kobo", "total_debit_kobo");
        assertThat(jdbc.queryForObject("select count(*) from transfers", Integer.class)).isZero();
    }

    @Test
    void existingRowsKeepTheirNumbersAndBecomeNaira() {
        migrateTo("13");
        insertOldStyle("R1", "INTERNAL", 2_000_000, 0, 0, 5_000, 2_005_000);
        insertOldStyle("R2", "EXTERNAL", 500_000, 5_000, 375, 0, 505_375);

        migrateTo("14");

        Map<String, Object> r1 = jdbc.queryForMap("select * from transfers where reference = 'R1'");
        assertThat(r1.get("amount_minor")).isEqualTo(2_000_000L);
        assertThat(r1.get("fee_minor")).isEqualTo(0L);
        assertThat(r1.get("stamp_duty_minor")).isEqualTo(5_000L);
        assertThat(r1.get("total_debit_minor")).isEqualTo(2_005_000L);
        assertThat(r1.get("currency")).isEqualTo("NGN");
        assertThat(r1.get("target_currency")).isNull();
        assertThat(r1.get("target_amount_minor")).isNull();
        assertThat(r1.get("quote_id")).isNull();

        Map<String, Object> r2 = jdbc.queryForMap("select * from transfers where reference = 'R2'");
        assertThat(r2.get("amount_minor")).isEqualTo(500_000L);
        assertThat(r2.get("fee_minor")).isEqualTo(5_000L);
        assertThat(r2.get("vat_minor")).isEqualTo(375L);
        assertThat(r2.get("total_debit_minor")).isEqualTo(505_375L);
        assertThat(r2.get("currency")).isEqualTo("NGN");
        assertThat(jdbc.queryForObject("select count(*) from transfers", Integer.class)).isEqualTo(2);
    }

    @Test
    void newRowsMustNameTheirCurrency() {
        migrateTo("14");

        assertThat(jdbc.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = ? and table_name = 'transfers' and column_name = 'currency'",
                String.class, SCHEMA)).isNull();
        assertThatThrownBy(() -> jdbc.update(
                "insert into transfers (reference, type, status, source_account_number, destination_account_number,"
                        + " amount_minor, total_debit_minor, idempotency_key, request_hash)"
                        + " values ('R3', 'INTERNAL', 'SUCCESSFUL', '1000000001', '1000000002', 100, 100, 'k3', 'h')"))
                .hasMessageContaining("currency");
    }

    @Test
    void theExistingChecksAndUniquenessStillHold() {
        migrateTo("14");
        String insert = "insert into transfers (reference, type, status, source_account_number,"
                + " destination_account_number, currency, amount_minor, total_debit_minor, idempotency_key, request_hash)"
                + " values (?, ?, 'SUCCESSFUL', '1000000001', '1000000002', 'USD', ?, ?, ?, 'h')";

        jdbc.update(insert, "R4", "INTERNAL", 100L, 100L, "k4");                         // a non-NGN row is accepted

        assertThatThrownBy(() -> jdbc.update(insert, "R5", "INTERNAL", 0L, 0L, "k5"))    // amount must stay positive
                .hasMessageContaining("ck_transfers_amount");
        assertThatThrownBy(() -> jdbc.update(insert, "R6", "BOGUS", 100L, 100L, "k6"))   // type check stays
                .hasMessageContaining("ck_transfers_type");
        assertThatThrownBy(() -> jdbc.update(insert, "R7", "INTERNAL", 100L, 100L, "k4")) // same source and key
                .hasMessageContaining("uq_transfers_idempotency");
    }
}