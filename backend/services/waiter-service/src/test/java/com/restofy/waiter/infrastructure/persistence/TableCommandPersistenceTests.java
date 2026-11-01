package com.restofy.waiter.infrastructure.persistence;

import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers): Flyway applies V101-V105, Hibernate validates the new entity against it, and
 * the database itself enforces the rules of the table command record: one unanswered close per main session, one
 * unanswered cleaning per table, one completed close per session, the shape of every status, the reconciliation
 * warning on completed closes only and the tenant-safe link from a cancelled call to the command that ended it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// No surrounding transaction: PostgreSQL aborts a transaction after the first constraint violation, which would
// hide the later assertions. Every statement commits on its own and the tables are emptied after each test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TableCommandPersistenceTests {

    private static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final UUID TABLE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TableCommandRepository repository;

    @BeforeEach
    @AfterEach
    void emptyTables() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM service_call_events");
        jdbc.update("DELETE FROM service_requests");
        jdbc.update("DELETE FROM table_commands");
    }

    private UUID insertCommand(UUID restaurant, String type, UUID session, String status, String key) {
        UUID id = UUID.randomUUID();
        boolean isFinal = status.equals("COMPLETED") || status.equals("REJECTED");
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO table_commands (id, restaurant_id, command_type, table_id, table_session_id, expected_version,
                                            requested_by_user_id, idempotency_key, request_digest, status, rejection_code,
                                            qr_closed_at, ended_guest_sessions, cancelled_call_count, table_version_after,
                                            ready_for_new_customer, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 3, ?, ?, 'digest', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, restaurant, type, TABLE, session, STAFF, key, status,
                status.equals("REJECTED") ? "SESSION_NOT_OPEN" : null,
                status.equals("COMPLETED") && type.equals("CLOSE") ? at : null,
                status.equals("COMPLETED") && type.equals("CLOSE") ? 2 : null,
                status.equals("COMPLETED") && type.equals("CLOSE") ? 1 : null,
                status.equals("COMPLETED") && type.equals("CLEAN") ? 9L : null,
                status.equals("COMPLETED") && type.equals("CLEAN") ? Boolean.TRUE : null,
                at, at, isFinal ? at : null);
        return id;
    }

    private UUID close(String status, String key) {
        return insertCommand(RESTAURANT, "CLOSE", SESSION, status, key);
    }

    private void insertCall(UUID id, UUID restaurant, String status, String reason, Timestamp cancelledAt, UUID command) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type,
                                              status, resolved_by_user_id, resolved_at, closure_reason, cancelled_at,
                                              cancelled_by_command_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'BILL', ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, restaurant, TABLE, SESSION, UUID.randomUUID(), status,
                status.equals("RESOLVED") ? STAFF : null, status.equals("RESOLVED") ? now : null,
                reason, cancelledAt, command, now, now);
    }

    // ----- the record -----

    @Test
    void anOpenCloseAndAnOpenCleaningAreStoredAndReadBackByTheEntity() {
        TableCommand.Origin origin = new TableCommand.Origin(UUID.randomUUID(), RESTAURANT, TABLE, 3, STAFF, "key-0123456789",
                "digest", Instant.now().truncatedTo(ChronoUnit.MICROS));
        TableCommand saved = repository.saveAndFlush(TableCommand.close(origin, SESSION, "Müşteri ayrıldı"));
        assertEquals(0L, saved.getVersion());

        TableCommand loaded = repository.findById(origin.id()).orElseThrow();
        assertEquals(TableCommandType.CLOSE, loaded.getType());
        assertEquals(TableCommandStatus.PENDING, loaded.getStatus());
        assertEquals("Müşteri ayrıldı", loaded.getReasonText());
        assertEquals(SESSION, loaded.getTableSessionId());
        assertEquals(loaded.getId(), repository.findByIdAndRestaurantIdAndTableId(origin.id(), RESTAURANT, TABLE).orElseThrow().getId());
        assertTrue(repository.findByIdAndRestaurantIdAndTableId(origin.id(), OTHER_RESTAURANT, TABLE).isEmpty(),
                "Another restaurant's command id looks like a missing one");
        assertTrue(repository.findByIdAndRestaurantIdAndTableId(origin.id(), RESTAURANT, UUID.randomUUID()).isEmpty());

        assertFalse(loaded.isReconciliationRequired());
        loaded.completeClose(Instant.now().truncatedTo(ChronoUnit.MICROS), 2, 1, true, Instant.now().truncatedTo(ChronoUnit.MICROS));
        TableCommand completed = repository.saveAndFlush(loaded);
        assertEquals(1L, completed.getVersion());
        TableCommand reread = repository.findById(origin.id()).orElseThrow();
        assertEquals(TableCommandStatus.COMPLETED, reread.getStatus());
        assertTrue(reread.isReconciliationRequired(), "QR's K13 warning survives with the completed close");
    }

    @Test
    void onlyACompletedCloseCanCarryTheReconciliationWarning() {
        UUID completed = close("COMPLETED", "key-completed-1");
        jdbc.update("UPDATE table_commands SET reconciliation_required = true WHERE id = ?", completed);
        for (String status : List.of("PENDING", "IN_PROGRESS", "UNKNOWN", "REJECTED")) {
            UUID open = insertCommand(RESTAURANT, "CLOSE", UUID.randomUUID(), status, "key-" + UUID.randomUUID());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE table_commands SET reconciliation_required = true WHERE id = ?", open),
                    "A " + status + " close is no confirmed closure");
        }
        UUID cleaning = insertCommand(RESTAURANT, "CLEAN", null, "PENDING", "key-clean-00001");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE table_commands SET reconciliation_required = true WHERE id = ?", cleaning));
    }

    @Test
    void theWorkListsOfTheReconcilerAreTenantAndStateAware() {
        UUID unknown = close("UNKNOWN", "key-unknown-01");
        close("COMPLETED", "key-completed-1");
        insertCommand(OTHER_RESTAURANT, "CLEAN", null, "PENDING", "key-other-0001");
        Instant future = Instant.now().plusSeconds(3600);

        List<TableCommand> due = repository.findDueForReconciliation(TableCommandStatus.OPEN, future, Instant.now().minusSeconds(3600),
                PageRequest.of(0, 10));
        assertEquals(2, due.size(), "Open commands of every restaurant are checked; final ones are not");
        assertTrue(due.stream().noneMatch(c -> c.getStatus() == TableCommandStatus.COMPLETED));
        assertTrue(repository.findDueForReconciliation(TableCommandStatus.OPEN, Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(7200), PageRequest.of(0, 10)).isEmpty(), "Not quiet long enough");
        assertTrue(repository.findDueForReconciliation(TableCommandStatus.OPEN, future, Instant.now().plusSeconds(60),
                PageRequest.of(0, 10)).isEmpty(), "Too old to be checked automatically");

        assertEquals(List.of(unknown), repository.findByRestaurantIdAndStatusInOrderByCreatedAtDesc(RESTAURANT, TableCommandStatus.OPEN)
                .stream().map(TableCommand::getId).toList());
        assertTrue(repository.existsByRestaurantIdAndTableIdAndTypeAndStatusIn(RESTAURANT, TABLE, TableCommandType.CLOSE,
                TableCommandStatus.OPEN));
        assertTrue(!repository.existsByRestaurantIdAndTableIdAndTypeAndStatusIn(OTHER_RESTAURANT, TABLE, TableCommandType.CLOSE,
                TableCommandStatus.OPEN));
        assertTrue(repository.existsByRestaurantIdAndTableSessionIdAndTypeAndStatus(RESTAURANT, SESSION, TableCommandType.CLOSE,
                TableCommandStatus.COMPLETED));
    }

    @Test
    void closedSessionsWithAnUnresolvedCallAreFoundForTheSessionOfTheirOwnRestaurantOnly() {
        UUID closed = close("COMPLETED", "key-completed-1");
        insertCall(UUID.randomUUID(), OTHER_RESTAURANT, "OPEN", null, null, null);   // same session id, another restaurant
        assertTrue(repository.findClosedSessionsWithUnresolvedCalls(PageRequest.of(0, 10)).isEmpty());
        insertCall(UUID.randomUUID(), RESTAURANT, "RESOLVED", null, null, null);
        assertTrue(repository.findClosedSessionsWithUnresolvedCalls(PageRequest.of(0, 10)).isEmpty());
        insertCall(UUID.randomUUID(), RESTAURANT, "OPEN", null, null, null);
        assertEquals(List.of(closed), repository.findClosedSessionsWithUnresolvedCalls(PageRequest.of(0, 10)).stream()
                .map(TableCommand::getId).toList());
    }

    // ----- uniqueness -----

    @Test
    void onlyOneUnansweredCloseMayExistPerMainSession() {
        close("PENDING", "key-pending-001");
        for (String status : List.of("PENDING", "IN_PROGRESS", "UNKNOWN")) {
            assertThrows(DataIntegrityViolationException.class, () -> close(status, "key-" + UUID.randomUUID()),
                    "A second " + status + " close for the session is refused by the database");
        }
        // Answered closes and closes of other sessions or restaurants do not compete.
        close("REJECTED", "key-rejected-01");
        insertCommand(RESTAURANT, "CLOSE", UUID.randomUUID(), "PENDING", "key-othersess-1");
        insertCommand(OTHER_RESTAURANT, "CLOSE", SESSION, "PENDING", "key-otherrest-1");
    }

    @Test
    void aSessionCanBeClosedOnceAndAnUnansweredCleaningIsUniquePerTable() {
        close("COMPLETED", "key-completed-1");
        assertThrows(DataIntegrityViolationException.class, () -> close("COMPLETED", "key-completed-2"));
        close("PENDING", "key-pending-001");   // a late attempt may exist; only a second completion is refused

        insertCommand(RESTAURANT, "CLEAN", null, "UNKNOWN", "key-clean-0001");
        assertThrows(DataIntegrityViolationException.class, () -> insertCommand(RESTAURANT, "CLEAN", null, "PENDING", "key-clean-0002"));
        insertCommand(RESTAURANT, "CLEAN", null, "COMPLETED", "key-clean-0003");
        insertCommand(RESTAURANT, "CLEAN", null, "REJECTED", "key-clean-0004");
    }

    @Test
    void theIdempotencyKeyIsUniquePerRestaurantAndStaffMember() {
        close("REJECTED", "key-shared-0001");
        assertThrows(DataIntegrityViolationException.class, () -> close("REJECTED", "key-shared-0001"));
        insertCommand(OTHER_RESTAURANT, "CLOSE", SESSION, "REJECTED", "key-shared-0001");
        jdbc.update("""
                INSERT INTO table_commands (id, restaurant_id, command_type, table_id, table_session_id, expected_version,
                                            requested_by_user_id, idempotency_key, request_digest, status, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'CLOSE', ?, gen_random_uuid(), 1, gen_random_uuid(), 'key-shared-0001', 'd',
                        'PENDING', now(), now())
                """, RESTAURANT, TABLE);
    }

    // ----- shape -----

    @Test
    void everyStatusAndTypeHasItsRequiredShape() {
        String insert = """
                INSERT INTO table_commands (id, restaurant_id, command_type, table_id, table_session_id, expected_version,
                                            requested_by_user_id, idempotency_key, request_digest, reason_text, status,
                                            rejection_code, completed_at, qr_closed_at, ended_guest_sessions, cancelled_call_count,
                                            table_version_after, ready_for_new_customer, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?, ?, 'd', ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """;
        UUID session = UUID.randomUUID();
        // A close needs its session, a cleaning must not have one.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, null, STAFF,
                "key-shape-0001",
                null, "PENDING", null, null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLEAN", TABLE, session, STAFF,
                "key-shape-0002",
                null, "PENDING", null, null, null, null, null, null, null));
        // A reason belongs to a close, and is short.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLEAN", TABLE, null, STAFF,
                "key-shape-0003",
                "why", "PENDING", null, null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0004",
                "x".repeat(301), "PENDING", null, null, null, null, null, null, null));
        // A rejection carries its code and only a rejection does; a final status carries its completion time.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0005",
                null, "REJECTED", null, Timestamp.from(Instant.now()), null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0006",
                null, "UNKNOWN", "SESSION_NOT_OPEN", null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0007",
                null, "REJECTED", "SESSION_NOT_OPEN", null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0008",
                null, "PENDING", null, Timestamp.from(Instant.now()), null, null, null, null, null));
        // A completed close names what QR confirmed; a completed cleaning the table version and readiness.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0009",
                null, "COMPLETED", null, Timestamp.from(Instant.now()), null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLEAN", TABLE, null, STAFF,
                "key-shape-0010",
                null, "COMPLETED", null, Timestamp.from(Instant.now()), null, null, null, null, null));
        // Unknown status and type, negative counts and versions.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0011",
                null, "DONE", null, null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "OPEN", TABLE, session, STAFF,
                "key-shape-0012",
                null, "PENDING", null, null, null, null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF,
                "key-shape-0013",
                null, "COMPLETED", null, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), -1, 0, null, null));
        // And the valid shapes go in.
        jdbc.update(insert, RESTAURANT, "CLOSE", TABLE, session, STAFF, "key-shape-0020",
                "Müşteri ayrıldı", "COMPLETED", null, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), 3, 2, null, null);
        jdbc.update(insert, RESTAURANT, "CLEAN", TABLE, null, STAFF, "key-shape-0021",
                null, "COMPLETED", null, Timestamp.from(Instant.now()), null, null, null, 9L, Boolean.FALSE);
    }

    // ----- calls that end with a closed session -----

    @Test
    void aCancelledCallNeedsItsReasonItsTimeAndTheCommandOfItsOwnRestaurant() {
        UUID command = close("COMPLETED", "key-completed-1");
        Timestamp at = Timestamp.from(Instant.now());
        insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", at, command);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", null, at, command), "no reason");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", null, command), "no time");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", at, null), "no command");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", "SOMETHING_ELSE", at, command), "unknown reason");
        // A call that was not cancelled cannot carry a closure.
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "OPEN", "TABLE_SESSION_CLOSED", at, command));
        // The command must belong to the call's own restaurant (composite foreign key).
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), OTHER_RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", at, command));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCall(UUID.randomUUID(), RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", at, UUID.randomUUID()));
    }

    @Test
    void theAuditTrailAcceptsTheCancelledEventAndNothingElseNew() {
        UUID command = close("COMPLETED", "key-completed-1");
        UUID call = UUID.randomUUID();
        insertCall(call, RESTAURANT, "CANCELLED", "TABLE_SESSION_CLOSED", Timestamp.from(Instant.now()), command);
        String insert = """
                INSERT INTO service_call_events (id, restaurant_id, service_request_id, call_version, event_type, from_status,
                                                 to_status, actor_type, actor_id, occurred_at, payload)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'OPEN', 'CANCELLED', 'STAFF', ?, now(), '{}'::jsonb)
                """;
        jdbc.update(insert, RESTAURANT, call, 1, "CANCELLED", STAFF);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, RESTAURANT, call, 2, "ARCHIVED", STAFF));
    }
}
