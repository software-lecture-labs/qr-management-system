package com.restofy.waiter.infrastructure.persistence;

import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL (Testcontainers): Flyway applies V101-V105, Hibernate validates the entity against them, and
 * the database itself enforces the call constraints, the single active call per guest and type, the tenant
 * foreign keys of the audit tables and the delivery queue states.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// No surrounding transaction: PostgreSQL aborts a transaction after the first constraint violation, which
// would hide the later assertions. Every statement commits on its own and the tables are emptied after each test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServiceRequestPersistenceTests {

    private static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID TABLE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServiceRequestRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    @AfterEach
    void emptyTables() {
        jdbc.update("DELETE FROM order_serve_commands");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM service_call_events");
        jdbc.update("DELETE FROM service_requests");
        jdbc.update("DELETE FROM table_commands");
    }

    private void insert(UUID id, UUID restaurant, UUID session, String type, String status, Instant createdAt) {
        insert(id, restaurant, session, UUID.randomUUID(), type, status, createdAt);
    }

    private void insert(UUID id, UUID restaurant, UUID session, UUID guest, String type, String status, Instant createdAt) {
        Timestamp at = Timestamp.from(createdAt);
        boolean claimed = status.equals("CLAIMED");
        boolean resolved = status.equals("RESOLVED");
        boolean cancelled = status.equals("CANCELLED");
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type,
                                              message, status, claimed_by_user_id, claimed_at, resolved_by_user_id,
                                              resolved_at, closure_reason, cancelled_at, cancelled_by_command_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, restaurant, TABLE, session, guest, type, "mesaj", status,
                claimed ? UUID.randomUUID() : null, claimed ? at : null,
                resolved ? UUID.randomUUID() : null, resolved ? at : null,
                cancelled ? "TABLE_SESSION_CLOSED" : null, cancelled ? at : null,
                cancelled ? closingCommand(restaurant, session) : null, at);
    }

    /** A call only ends CANCELLED together with a confirmed close of its session (WAITER-04); this is that close. */
    private UUID closingCommand(UUID restaurant, UUID session) {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM table_commands WHERE restaurant_id = ? AND table_session_id = ?",
                UUID.class, restaurant, session);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO table_commands (id, restaurant_id, command_type, table_id, table_session_id, expected_version,
                                            requested_by_user_id, idempotency_key, request_digest, status, qr_closed_at,
                                            ended_guest_sessions, cancelled_call_count, created_at, updated_at, completed_at)
                VALUES (?, ?, 'CLOSE', ?, ?, 1, ?, ?, 'digest', 'COMPLETED', now(), 1, 1, now(), now(), now())
                """, id, restaurant, TABLE, session, UUID.randomUUID(), "key-" + id);
        return id;
    }

    private void insertEvent(UUID eventId, UUID restaurant, UUID call, long version) {
        jdbc.update("""
                INSERT INTO service_call_events (id, restaurant_id, service_request_id, call_version, event_type, to_status,
                                                 actor_type, actor_id, occurred_at, payload)
                VALUES (?, ?, ?, ?, 'CREATED', 'OPEN', 'GUEST', ?, now(), '{}'::jsonb)
                """, eventId, restaurant, call, version, UUID.randomUUID());
    }

    private void insertDelivery(UUID eventId, UUID restaurant, UUID call, long version, String status, boolean delivered) {
        jdbc.update("""
                INSERT INTO notification_deliveries (event_id, restaurant_id, service_request_id, call_version, status,
                                                     next_attempt_at, delivered_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), ?, now(), now())
                """, eventId, restaurant, call, version, status, delivered ? Timestamp.from(Instant.now()) : null);
    }

    private static String constraintOf(DataIntegrityViolationException error) {
        return error.getMostSpecificCause().getMessage();
    }

    @Test
    void flywayAppliedV101ToV105AndHibernateValidatesTheSchema() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertEquals(List.of("101", "102", "103", "104", "105"), versions,
                "Waiter migrations must stay inside the V101-V110 range, in order and without gaps");
        assertEquals("validate", entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"));
    }

    @Test
    void lookupIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('service_requests', 'notification_deliveries')", String.class);
        for (String expected : List.of("ix_service_requests_session", "ix_service_requests_listing", "ix_service_requests_table",
                "uq_service_requests_active_per_guest", "ix_notification_deliveries_due", "ix_notification_deliveries_call")) {
            assertTrue(indexes.contains(expected), expected);
        }
    }

    @Test
    void databaseRejectsUnknownTypeUnknownStatusAndNegativeVersion() {
        UUID id = UUID.randomUUID();
        var typeError = assertThrows(DataIntegrityViolationException.class,
                () -> insert(id, RESTAURANT, SESSION, "COMPLAINT", "OPEN", Instant.now()));
        assertTrue(constraintOf(typeError).contains("ck_service_requests_type"));
        var statusError = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), RESTAURANT, SESSION, "WAITER", "ACKNOWLEDGED", Instant.now()));
        assertTrue(constraintOf(statusError).contains("ck_service_requests_status"));
        var versionError = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type,
                                              status, version)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'OPEN', -1)
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID()));
        assertTrue(constraintOf(versionError).contains("ck_service_requests_version"));
    }

    @Test
    void requiredColumnsAreNotNullAndIdIsUnique() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO service_requests (id, table_id, table_session_id, guest_session_id, request_type, status) VALUES (?, ?, ?, ?, "
                        + "'WAITER', 'OPEN')",
                UUID.randomUUID(), TABLE, SESSION, UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        insert(id, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> insert(id, RESTAURANT, SESSION, "HELP", "OPEN", Instant.now()));
    }

    @Test
    void newRowsMustCarryTheTableAndConsistentClaimAndResolveFacts() {
        var noTable = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_session_id, guest_session_id, request_type, status)
                VALUES (?, ?, ?, ?, 'WAITER', 'OPEN')
                """, UUID.randomUUID(), RESTAURANT, SESSION, UUID.randomUUID()));
        assertTrue(constraintOf(noTable).contains("ck_service_requests_table"));

        var claimedWithoutClaimer = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type, status)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'CLAIMED')
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID()));
        assertTrue(constraintOf(claimedWithoutClaimer).contains("ck_service_requests_claimed"));

        var resolvedWithoutResolver = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type, status)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'RESOLVED')
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID()));
        assertTrue(constraintOf(resolvedWithoutResolver).contains("ck_service_requests_resolved"));

        var openWithResolver = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type,
                                              status, resolved_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'OPEN', ?)
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(constraintOf(openWithResolver).contains("ck_service_requests_open"));
    }

    @Test
    void messageLengthIsBoundedByTheDatabaseToo() {
        UUID guest = UUID.randomUUID();
        var empty = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id,
                                              request_type, status, message)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'OPEN', '')
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, guest));
        assertTrue(constraintOf(empty).contains("ck_service_requests_message"));
        var tooLong = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id,
                                              request_type, status, message)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'OPEN', ?)
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, guest, "x".repeat(301)));
        assertTrue(constraintOf(tooLong).contains("ck_service_requests_message"));
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id,
                                              request_type, status, message)
                VALUES (?, ?, ?, ?, ?, 'WAITER', 'OPEN', ?)
                """, UUID.randomUUID(), RESTAURANT, TABLE, SESSION, guest, "ç".repeat(300));
    }

    @Test
    void defaultsAreFilledByTheDatabase() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_requests (id, restaurant_id, table_id, table_session_id, guest_session_id, request_type, status)
                VALUES (?, ?, ?, ?, ?, 'BILL', 'OPEN')
                """, id, RESTAURANT, TABLE, SESSION, UUID.randomUUID());
        ServiceRequest loaded = repository.findById(id).orElseThrow();
        assertEquals(0L, loaded.getVersion());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertEquals(TABLE, loaded.getTableId());
        assertEquals(ServiceRequestType.BILL, loaded.getRequestType());
        assertEquals(ServiceRequestStatus.OPEN, loaded.getStatus());
    }

    @Test
    void oneActiveCallPerGuestAndTypeButNeverMergedAcrossGuestsOrTypes() {
        UUID guest = UUID.randomUUID();
        insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "WAITER", "OPEN", Instant.now());
        var duplicate = assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "WAITER", "CLAIMED", Instant.now()));
        assertTrue(constraintOf(duplicate).contains("uq_service_requests_active_per_guest"));

        // Another type, another guest of the same table, and the same guest in another restaurant are all fine.
        insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "BILL", "OPEN", Instant.now());
        insert(UUID.randomUUID(), RESTAURANT, SESSION, UUID.randomUUID(), "WAITER", "OPEN", Instant.now());
        insert(UUID.randomUUID(), OTHER_RESTAURANT, SESSION, guest, "WAITER", "OPEN", Instant.now());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM service_requests", Integer.class));
    }

    @Test
    void aFinishedCallFreesTheGuestToRaiseTheSameTypeAgain() {
        UUID guest = UUID.randomUUID();
        insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "WAITER", "RESOLVED", Instant.now());
        insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "WAITER", "CANCELLED", Instant.now());
        insert(UUID.randomUUID(), RESTAURANT, SESSION, guest, "WAITER", "OPEN", Instant.now());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM service_requests", Integer.class));
    }

    @Test
    void unresolvedCallsAreScopedToRestaurantAndSessionAndOrderedOldestFirst() {
        Instant base = Instant.parse("2026-10-16T09:00:00Z");
        UUID second = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        insert(second, RESTAURANT, SESSION, "BILL", "CLAIMED", base.plusSeconds(60));
        insert(first, RESTAURANT, SESSION, "WAITER", "OPEN", base);
        insert(UUID.randomUUID(), RESTAURANT, SESSION, "HELP", "RESOLVED", base);
        insert(UUID.randomUUID(), RESTAURANT, SESSION, "HELP", "CANCELLED", base);
        insert(UUID.randomUUID(), RESTAURANT, OTHER_SESSION, "HELP", "OPEN", base);
        insert(UUID.randomUUID(), OTHER_RESTAURANT, SESSION, "HELP", "OPEN", base);

        List<ServiceRequest> found = repository.findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
                RESTAURANT, List.of(SESSION), ServiceRequestStatus.UNRESOLVED);

        assertEquals(List.of(first, second), found.stream().map(ServiceRequest::getId).toList());
        assertTrue(found.stream().allMatch(call -> call.getRestaurantId().equals(RESTAURANT)));
        assertFalse(found.stream().anyMatch(call -> call.getTableSessionId().equals(OTHER_SESSION)));

        List<ServiceRequest> both = repository.findByRestaurantIdAndTableSessionIdInAndStatusInOrderByCreatedAtAscIdAsc(
                RESTAURANT, List.of(SESSION, OTHER_SESSION), ServiceRequestStatus.UNRESOLVED);
        assertEquals(3, both.size());
    }

    @Test
    void tenantScopedLookupDoesNotRevealAnotherRestaurantsCall() {
        UUID id = UUID.randomUUID();
        insert(id, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        assertTrue(repository.findByIdAndRestaurantId(id, RESTAURANT).isPresent());
        assertTrue(repository.findByIdAndRestaurantId(id, OTHER_RESTAURANT).isEmpty());
    }

    @Test
    void newCallsArePersistedNotMergedAndGetVersionZero() {
        UUID id = UUID.randomUUID();
        ServiceRequest created = repository.saveAndFlush(ServiceRequest.open(id, RESTAURANT, TABLE, SESSION,
                UUID.randomUUID(), ServiceRequestType.HELP, "Yardım", Instant.now().truncatedTo(ChronoUnit.MICROS)));
        assertEquals(0L, created.getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM service_requests WHERE id = ?", Integer.class, id));
    }

    @Test
    void staleVersionIsRejectedByTheVersionColumn() {
        UUID id = UUID.randomUUID();
        insert(id, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        ServiceRequest firstReader = repository.findById(id).orElseThrow();
        ServiceRequest secondReader = repository.findById(id).orElseThrow();
        firstReader.resolve(UUID.randomUUID(), Instant.now());
        repository.saveAndFlush(firstReader);
        secondReader.resolve(UUID.randomUUID(), Instant.now());
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(secondReader));
    }

    @Test
    void auditEventsAreTenantScopedAndUniquePerCallVersion() {
        UUID call = UUID.randomUUID();
        insert(call, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        insertEvent(UUID.randomUUID(), RESTAURANT, call, 0);
        var sameVersion = assertThrows(DataIntegrityViolationException.class,
                () -> insertEvent(UUID.randomUUID(), RESTAURANT, call, 0));
        assertTrue(constraintOf(sameVersion).contains("uq_service_call_events_version"));
        var wrongTenant = assertThrows(DataIntegrityViolationException.class,
                () -> insertEvent(UUID.randomUUID(), OTHER_RESTAURANT, call, 1));
        assertTrue(constraintOf(wrongTenant).contains("fk_service_call_events_call"));
        var badType = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_call_events (id, restaurant_id, service_request_id, call_version, event_type, to_status,
                                                 actor_type, actor_id, occurred_at, payload)
                VALUES (?, ?, ?, 2, 'DELETED', 'OPEN', 'GUEST', ?, now(), '{}'::jsonb)
                """, UUID.randomUUID(), RESTAURANT, call, UUID.randomUUID()));
        assertTrue(constraintOf(badType).contains("ck_service_call_events_type"));
    }

    @Test
    void deliveryRowsNeedAnEventAndOnlyKnownStates() {
        UUID call = UUID.randomUUID();
        insert(call, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        UUID event = UUID.randomUUID();
        var withoutEvent = assertThrows(DataIntegrityViolationException.class,
                () -> insertDelivery(event, RESTAURANT, call, 0, "PENDING", false));
        assertTrue(constraintOf(withoutEvent).contains("fk_notification_deliveries_event"));
        insertEvent(event, RESTAURANT, call, 0);
        var unknownState = assertThrows(DataIntegrityViolationException.class,
                () -> insertDelivery(event, RESTAURANT, call, 0, "SENT", false));
        assertTrue(constraintOf(unknownState).contains("ck_notification_deliveries_status"));
        var deliveredWithoutTime = assertThrows(DataIntegrityViolationException.class,
                () -> insertDelivery(event, RESTAURANT, call, 0, "DELIVERED", false));
        assertTrue(constraintOf(deliveredWithoutTime).contains("ck_notification_deliveries_delivered"));
        insertDelivery(event, RESTAURANT, call, 0, "DELIVERED", true);
        assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(event, RESTAURANT, call, 0, "PENDING", false));
    }

    @Test
    void idempotencyKeysAreUniquePerTenantActorAndOperation() {
        UUID call = UUID.randomUUID();
        insert(call, RESTAURANT, SESSION, "WAITER", "OPEN", Instant.now());
        String sql = """
                INSERT INTO idempotency_records (id, restaurant_id, actor_scope, operation, idempotency_key, request_digest,
                                                 service_request_id, response_status, created_at)
                VALUES (?, ?, ?, ?, 'key-0000001', ?, ?, ?, now())
                """;
        String digest = "a".repeat(64);
        jdbc.update(sql, UUID.randomUUID(), RESTAURANT, "STAFF:1", "RESOLVE", digest, call, 200);
        var repeat = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, UUID.randomUUID(), RESTAURANT, "STAFF:1", "RESOLVE", digest, call, 200));
        assertTrue(constraintOf(repeat).contains("uq_idempotency_records_key"));
        // Another actor or another operation may reuse the same key text.
        jdbc.update(sql, UUID.randomUUID(), RESTAURANT, "STAFF:2", "RESOLVE", digest, call, 200);
        jdbc.update(sql, UUID.randomUUID(), RESTAURANT, "STAFF:1", "CLAIM", digest, call, 200);
        var badStatus = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, UUID.randomUUID(), RESTAURANT, "STAFF:3", "CLAIM", digest, call, 500));
        assertTrue(constraintOf(badStatus).contains("ck_idempotency_records_status"));
        var foreignTenant = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, UUID.randomUUID(), OTHER_RESTAURANT, "STAFF:1", "RESOLVE", digest, call, 200));
        assertTrue(constraintOf(foreignTenant).contains("fk_idempotency_records_call"));
    }

    private static final String SERVE_COMMAND_INSERT = """
            INSERT INTO order_serve_commands (id, restaurant_id, order_id, table_session_id, expected_version,
                   requested_by_user_id, idempotency_key, request_digest, status, rejection_code, created_at, updated_at,
                   completed_at)
            VALUES (?, ?, ?, ?, 4, ?, ?, ?, ?, ?, now(), now(), ?)
            """;

    private void insertServeCommand(UUID staff, String key, String status, String rejectionCode, boolean completed) {
        jdbc.update(SERVE_COMMAND_INSERT, UUID.randomUUID(), RESTAURANT, UUID.randomUUID(), SESSION, staff, key,
                "a".repeat(64), status, rejectionCode, completed ? Timestamp.from(Instant.now()) : null);
    }

    @Test
    void serveCommandsAreUniquePerStaffMemberAndKeyAndOnlyHoldKnownStates() {
        UUID staff = UUID.randomUUID();
        insertServeCommand(staff, "key-00000001", "PENDING", null, false);
        var repeat = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000001", "UNKNOWN", null, false));
        assertTrue(constraintOf(repeat).contains("uq_order_serve_commands_key"));
        // Same key text for another staff member, and another key for the same staff member, are independent.
        insertServeCommand(UUID.randomUUID(), "key-00000001", "UNKNOWN", null, false);
        insertServeCommand(staff, "key-00000002", "CONFIRMED", null, true);
        var unknownState = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000003", "SERVED", null, false));
        assertTrue(constraintOf(unknownState).contains("ck_order_serve_commands_status"));
    }

    @Test
    void aServeCommandIsCompletedExactlyWhenOrderAnsweredAndARejectionAlwaysHasItsReason() {
        UUID staff = UUID.randomUUID();
        var rejectedWithoutReason = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000001", "REJECTED", null, true));
        assertTrue(constraintOf(rejectedWithoutReason).contains("ck_order_serve_commands_rejection"));
        var reasonWithoutRejection = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000002", "CONFIRMED", "ORDER_NOT_READY", true));
        assertTrue(constraintOf(reasonWithoutRejection).contains("ck_order_serve_commands_rejection"));
        var confirmedNotCompleted = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000003", "CONFIRMED", null, false));
        assertTrue(constraintOf(confirmedNotCompleted).contains("ck_order_serve_commands_completed"));
        var unknownButCompleted = assertThrows(DataIntegrityViolationException.class,
                () -> insertServeCommand(staff, "key-00000004", "UNKNOWN", null, true));
        assertTrue(constraintOf(unknownButCompleted).contains("ck_order_serve_commands_completed"));
        insertServeCommand(staff, "key-00000005", "REJECTED", "ORDER_NOT_READY", true);
    }

    @Test
    void serveCommandsReferenceNothingOutsideTheWaiterDatabase() {
        Integer foreignKeys = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'order_serve_commands' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);
        assertEquals(0, foreignKeys, "order_id and table_session_id are logical references to other services");
    }

    @Test
    void callStateOnlyChangesThroughDomainTransitions() {
        assertTrue(Arrays.stream(ServiceRequest.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("set")), "State changes go through claim and resolve");
        assertTrue(Arrays.stream(ServiceRequest.class.getConstructors()).findAny().isEmpty(),
                "No public constructor: calls are created through ServiceRequest.open");
        assertNull(ServiceRequest.open(UUID.randomUUID(), RESTAURANT, TABLE, SESSION, UUID.randomUUID(),
                ServiceRequestType.WAITER, null, Instant.now()).getMessage());
    }
}
