package com.academy.paybridge.remittance;

import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step B2: the remittance tables exist with their keys and checks. Starting the application also proves the entity
 * mappings match the tables (the schema is validated at start-up).
 */
@SpringBootTest
@ActiveProfiles("test")
class RemittanceMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    private static final String INSERT = "INSERT INTO remittances (reference, customer_id, quote_id, source_account_number, "
            + "source_currency, payout_currency, source_amount_minor, fee_minor, payout_amount_minor, purpose, status, "
            + "provider, idempotency_key, request_hash) VALUES (?, ?, gen_random_uuid(), '2000000001', 'USD', 'NGN', "
            + "%s, %s, %s, 'GIFT', ?, 'FLUTTERWAVE', 'key-12345678', 'hash')";

    private static String sqlState(Throwable t) {
        Throwable cause = t;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        return cause == null ? null : ((SQLException) cause).getSQLState();
    }

    @Test
    void bothTablesHaveTheExpectedColumns() {
        List<String> remittances = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'remittances'", String.class);
        List<String> attempts = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'remittance_attempts'", String.class);

        assertThat(remittances).contains("reference", "customer_id", "quote_id", "source_account_number", "source_currency",
                "payout_currency", "source_amount_minor", "fee_minor", "payout_amount_minor", "purpose", "status",
                "conversion_done", "attempts", "next_retry_at", "rate_difference_minor", "provider", "provider_payout_ref",
                "provider_status", "failure_reason", "idempotency_key", "request_hash", "version", "created_at",
                "updated_at", "completed_at");
        assertThat(attempts).contains("remittance_id", "attempt", "customer_reference", "provider_payout_ref", "result",
                "created_at");
    }

    @Test
    void theUniqueKeysExist() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid IN ('remittances'::regclass, 'remittance_attempts'::regclass)",
                String.class);

        assertThat(constraints).contains("uq_remittances_idempotency", "uq_remittance_attempt")
                .anyMatch(n -> n.startsWith("remittances_reference_key"))
                .anyMatch(n -> n.startsWith("remittances_quote_id_key"))
                .anyMatch(n -> n.startsWith("remittance_attempts_customer_reference_key"));
    }

    @Test
    void aBadStatusIsRefusedByTheCheck() {
        // PostgreSQL tests CHECK constraints before foreign keys, so a bad status shows as a check violation (23514).
        assertThatThrownBy(() -> jdbc.update(String.format(INSERT, "100", "0", "100"), "ref-bad-status", 999_999_999L, "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    @Test
    void zeroAmountsAndTooManyAttemptsAreRefused() {
        assertThatThrownBy(() -> jdbc.update(String.format(INSERT, "0", "0", "100"), "ref-zero-amount", 999_999_999L, "PENDING"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        assertThatThrownBy(() -> jdbc.update(String.format(INSERT, "100", "0", "0"), "ref-zero-payout", 999_999_999L, "PENDING"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO remittance_attempts (remittance_id, attempt, customer_reference, result) "
                + "VALUES (1, 4, 'ref-attempt-4', 'X')")).satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    @Test
    void aValidRowStillNeedsARealCustomerAndQuote() {
        // A good status passes the checks and then meets the foreign key (23503): the table is tied to customers and quotes.
        assertThatThrownBy(() -> jdbc.update(String.format(INSERT, "100", "0", "100"), "ref-no-customer", 999_999_999L, "PENDING"))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }
}