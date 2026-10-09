package com.academy.paybridge.support;

import java.sql.Connection;
import java.sql.DriverManager;

/** Lets integration tests skip themselves (instead of failing) when no test database is running. */
public final class TestDb {

    private TestDb() {
    }

    public static boolean available() {
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/paybridge_test");
        String user = System.getenv().getOrDefault("TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", System.getenv().getOrDefault("DB_PASSWORD", ""));
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
