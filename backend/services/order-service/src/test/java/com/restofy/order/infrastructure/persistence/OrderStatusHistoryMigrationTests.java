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

/**
 * The status history table (V44) on a clean, throw-away schema of the test database: the whole chain runs from nothing
 * and ends with an empty history. The migrations were consolidated before any shared database applied them, so there is no
 * earlier deployment whose orders would need a backfilled timeline.
 */
@SpringBootTest
class OrderStatusHistoryMigrationTests {

    @Autowired
    private DataSource dataSource;

    private Flyway flyway(String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
    }

    @Test
    void migrationsFromScratchEndWithTheHistoryTable() throws Exception {
        String schema = "history_scratch_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema).migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM order_status_history")) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
