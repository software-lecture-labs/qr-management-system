package com.restofy.kitchen.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL: the V89 constraints that keep the inbox a trustworthy, never-changing record. */
@SpringBootTest
class InboxEventSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private record Asked(UUID restaurant, UUID orderId, UUID requestId) {
	}

	private Asked asked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', 'WAITING', now(), 1, ?)
				""", UUID.randomUUID(), restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("""
				INSERT INTO cancellation_requests (id, restaurant_id, order_id, command_id, status, reason_code, requested_by_user_id, requested_at)
				VALUES (?, ?, ?, ?, 'REQUESTED', 'OTHER', ?, now())
				""", requestId, restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		return new Asked(restaurant, orderId, requestId);
	}

	private void insert(Asked a, String source, UUID eventId, String decision, String outcome, long version, int schema) {
		jdbc.update("""
				INSERT INTO inbox_events (id, source_service, event_id, event_type, schema_version, restaurant_id, order_id, request_id,
				    order_version, decision, decided_at, outcome, received_at)
				VALUES (?, ?, ?, 'OrderCancelled', ?, ?, ?, ?, ?, ?, now(), ?, now())
				""", UUID.randomUUID(), source, eventId, schema, a.restaurant(), a.orderId(), a.requestId(), version, decision, outcome);
	}

	private void insertRaw(UUID id, UUID eventId, UUID restaurantId, UUID orderId, UUID requestId) {
		jdbc.update("""
				INSERT INTO inbox_events (id, source_service, event_id, event_type, schema_version, restaurant_id, order_id, request_id,
				    order_version, decision, decided_at, outcome, received_at)
				VALUES (?, 'ORDER', ?, 'OrderCancelled', 1, ?, ?, ?, 2, 'APPROVED', now(), 'CANCELLED', now())
				""", id, eventId, restaurantId, orderId, requestId);
	}

	private void assertInvalidInsert(Asked asked, String decision, String outcome, long version, int schema) {
		UUID eventId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insert(asked, "ORDER", eventId, decision, outcome, version, schema));
	}

	@Test
	void anEventOfASenderIsRecordedOnlyOnce() {
		Asked a = asked();
		UUID eventId = UUID.randomUUID();
		insert(a, "ORDER", eventId, "APPROVED", "CANCELLED", 2, 1);

		assertThrows(DataIntegrityViolationException.class, () -> insert(a, "ORDER", eventId, "APPROVED", "CANCELLED", 2, 1));
		assertDoesNotThrow(() -> insert(a, "OTHER_SENDER", eventId, "APPROVED", "STALE", 2, 1));
		UUID otherEventId = UUID.randomUUID();
		assertDoesNotThrow(() -> insert(a, "ORDER", otherEventId, "REJECTED", "ALREADY_DECIDED", 2, 1));
	}

	@Test
	void theOrderAndTheRequestMustExistAndOutcomeDecisionVersionAndSchemaAreChecked() {
		Asked a = asked();
		Asked other = asked();
		UUID restaurantId = a.restaurant();
		UUID orderId = a.orderId();
		UUID requestId = a.requestId();
		UUID otherRestaurantId = other.restaurant();

		UUID unknownOrderId = UUID.randomUUID();
		UUID unknownOrderRowId = UUID.randomUUID();
		UUID unknownOrderEventId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insertRaw(unknownOrderRowId, unknownOrderEventId, restaurantId, unknownOrderId, requestId),
				"unknown order");
		UUID unknownRequestId = UUID.randomUUID();
		UUID unknownRequestRowId = UUID.randomUUID();
		UUID unknownRequestEventId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insertRaw(unknownRequestRowId, unknownRequestEventId, restaurantId, orderId, unknownRequestId),
				"unknown request");
		UUID otherRestaurantRowId = UUID.randomUUID();
		UUID otherRestaurantEventId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insertRaw(otherRestaurantRowId, otherRestaurantEventId, otherRestaurantId, orderId, requestId),
				"order of another restaurant");
		assertInvalidInsert(a, "MAYBE", "CANCELLED", 2, 1);
		assertInvalidInsert(a, "APPROVED", "UNKNOWN", 2, 1);
		assertInvalidInsert(a, "APPROVED", "CANCELLED", -1, 1);
		assertInvalidInsert(a, "APPROVED", "CANCELLED", 2, 0);
	}

	@Test
	void anInboxRecordIsNeverChangedOrDeleted() {
		Asked a = asked();
		UUID eventId = UUID.randomUUID();
		insert(a, "ORDER", eventId, "APPROVED", "NEEDS_RECONCILIATION", 2, 1);

		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE inbox_events SET outcome = 'CANCELLED' WHERE event_id = ?", eventId));
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE inbox_events SET order_version = 99 WHERE event_id = ?", eventId));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM inbox_events WHERE event_id = ?", eventId));
		assertEquals("NEEDS_RECONCILIATION", jdbc.queryForObject("SELECT outcome FROM inbox_events WHERE event_id = ?", String.class, eventId));
	}
}
