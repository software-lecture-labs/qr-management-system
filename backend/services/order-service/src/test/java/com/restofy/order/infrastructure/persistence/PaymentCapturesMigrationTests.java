package com.restofy.order.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payment capture tables (V46) on a clean, throw-away schema: the whole chain runs from nothing and ends with empty
 * capture and reconciliation tables. The migrations were consolidated before any shared database applied them, so no
 * earlier mechanism has to be carried over into these tables.
 */
@SpringBootTest
class PaymentCapturesMigrationTests {

    @Autowired
    private DataSource dataSource;

    private Flyway flyway(String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
    }

    @Test
    void aDatabaseWithoutAnyPaymentHistoryMigratesToEmptyCaptureTables() throws Exception {
        String schema = "captures_scratch_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema).migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                assertEquals(0, count(statement, "payment_captures"));
                assertEquals(0, count(statement, "payment_reconciliation_cases"));
                try (ResultSet rows = statement.executeQuery("SELECT version FROM flyway_schema_history"
                        + " ORDER BY installed_rank DESC LIMIT 1")) {
                    assertTrue(rows.next());
                    assertFalse(rows.getString(1).isBlank());
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static int count(Statement statement, String from) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + from)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
