package com.restofy.waiter.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrades a database that is already at V101 (the WAITER-01 state) to the latest Waiter migration, on a real
 * PostgreSQL. It proves that rows written before WAITER-02 survive, that the new rules apply to new rows, and
 * that the applied V101 is byte-for-byte what it was: its Flyway checksum would change if anyone edited it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MigrationUpgradeTests {

    private static final String PROBE_DATABASE = "upgrade_probe";
    /** Flyway checksum of the applied V101__create_service_requests.sql. Changing the file changes this value. */
    private static final int V101_CHECKSUM = 164032428;

    @Autowired
    private DataSource dataSource;

    private String probeUrl;

    @BeforeEach
    void createProbeDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            probeUrl = connection.getMetaData().getURL().replace("/" + connection.getCatalog(), "/" + PROBE_DATABASE);
            statement.execute("DROP DATABASE IF EXISTS " + PROBE_DATABASE);
            statement.execute("CREATE DATABASE " + PROBE_DATABASE);
        }
    }

    @AfterEach
    void dropProbeDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + PROBE_DATABASE);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(probeUrl, "test", "test").locations("classpath:db/migration")
                .target(target).load();
    }

    private Connection probe() throws SQLException {
        return DriverManager.getConnection(probeUrl, "test", "test");
    }

    private List<String> appliedVersions(Connection connection) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (rows.next()) versions.add(rows.getString(1));
        }
        return versions;
    }

    @Test
    void upgradingFromV101KeepsLegacyRowsAppliesTheNewRulesToNewRowsAndLeavesV101Untouched() throws SQLException {
        flyway("101").migrate();
        UUID legacy = UUID.randomUUID();
        try (Connection connection = probe(); Statement statement = connection.createStatement()) {
            assertEquals(List.of("101"), appliedVersions(connection));
            statement.execute("INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type, status) "
                    + "VALUES ('" + legacy + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'WAITER', 'OPEN')");
        }

        flyway("latest").migrate();

        try (Connection connection = probe(); Statement statement = connection.createStatement()) {
            assertEquals(List.of("101", "102", "103", "104", "105", "106"), appliedVersions(connection));
            try (var checksum = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '101'")) {
                assertTrue(checksum.next());
                assertEquals(V101_CHECKSUM, checksum.getInt(1), "V101 is applied history and must never be edited");
            }
            try (var legacyRow = statement.executeQuery("SELECT table_id, status, version FROM service_requests WHERE id = '" + legacy
                    + "'")) {
                assertTrue(legacyRow.next(), "A row written before WAITER-02 survives the upgrade");
                assertNull(legacyRow.getObject("table_id"));
                assertEquals("OPEN", legacyRow.getString("status"));
                assertEquals(0L, legacyRow.getLong("version"));
            }
            // WAITER-04: the command table exists, the legacy row has no closure, and a cancelled call must say why.
            try (var commands = statement.executeQuery("SELECT count(*) FROM table_commands")) {
                assertTrue(commands.next());
                assertEquals(0, commands.getInt(1));
            }
            try (var legacyRow = statement.executeQuery("SELECT closure_reason, cancelled_at, cancelled_by_command_id FROM "
                    + "service_requests WHERE id = '" + legacy + "'")) {
                assertTrue(legacyRow.next());
                assertNull(legacyRow.getObject(1));
                assertNull(legacyRow.getObject(2));
                assertNull(legacyRow.getObject(3));
            }
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO service_requests "
                    + "(id, restaurant_id, table_id, table_session_id, guest_session_id, request_type, status) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'WAITER', "
                            + "'CANCELLED')"));
            // New rows are held to the new rules.
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO service_requests "
                    + "(id, restaurant_id, table_session_id, guest_session_id, request_type, status) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'WAITER', 'OPEN')"));
            statement.execute("INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, "
                    + "request_type, status) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'WAITER', "
                            + "'OPEN')");
        }

        // Running again finds nothing to do and validates every checksum.
        flyway("latest").migrate();
        flyway("latest").validate();
    }
}
