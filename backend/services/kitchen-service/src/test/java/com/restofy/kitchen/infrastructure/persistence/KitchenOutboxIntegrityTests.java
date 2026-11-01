package com.restofy.kitchen.infrastructure.persistence;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Outbox data integrity on PostgreSQL: it agrees with the order, and the database refuses inconsistent rows. */
@SpringBootTest
class KitchenOutboxIntegrityTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID readyOrder(UUID restaurant) {
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);
		return orderId;
	}

	@Test
	void theEventAgreesWithTheOrderItDescribes() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = readyOrder(restaurant);

		Map<String, Object> order = jdbc.queryForMap(
				"SELECT version, ready_at, started_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
		Map<String, Object> event = jdbc.queryForMap(
				"SELECT aggregate_version, occurred_at, aggregate_id, event_type, schema_version FROM outbox_events WHERE restaurant_id = ?",
						restaurant);

		assertEquals(((Number) order.get("version")).longValue(), ((Number) event.get("aggregate_version")).longValue());
		assertEquals(order.get("ready_at"), event.get("occurred_at"));
		assertEquals(orderId, event.get("aggregate_id"));
		assertEquals("KitchenOrderReady", event.get("event_type"));
		assertEquals(1, event.get("schema_version"));
		Map<String, Object> delivery = jdbc.queryForMap("SELECT status, destination, attempt_count FROM outbox_deliveries WHERE "
				+ "restaurant_id = ? AND destination = 'ORDER'", restaurant);
		assertEquals("PENDING", delivery.get("status"));
		assertEquals("ORDER", delivery.get("destination"));
		assertEquals(0, delivery.get("attempt_count"));
	}

	@Test
	void theDatabaseRefusesInconsistentOutboxRows() {
		UUID restaurant = UUID.randomUUID();
		readyOrder(restaurant);
		UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE restaurant_id = ?", UUID.class, restaurant);
		UUID otherRestaurant = UUID.randomUUID();

		// A second delivery to the same destination for the same event.
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(restaurant, eventId, "ORDER", "PENDING", "now()", null));
		// A delivery that points at another restaurant's event (tenant mix-up through the composite key).
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(otherRestaurant, eventId, "OTHER", "PENDING", "now()", null));
		// DELIVERED without a delivery time, a delivery time without DELIVERED, PENDING without a next attempt.
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(restaurant, eventId, "A", "DELIVERED", null, null));
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(restaurant, eventId, "B", "FAILED", null, "now()"));
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(restaurant, eventId, "C", "PENDING", null, null));
		assertThrows(DataIntegrityViolationException.class, () -> insertDelivery(restaurant, eventId, "D", "SENT", "now()", null));
		// An event with a non-object payload or a schema version below 1.
		UUID arrayPayloadEventId = UUID.randomUUID();
		UUID arrayPayloadAggregateId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
				                           schema_version, payload, occurred_at)
				VALUES (?, ?, 'KITCHEN_ORDER', ?, 1, 'Other', 1, '[]'::jsonb, now())
				""", arrayPayloadEventId, restaurant, arrayPayloadAggregateId));
		UUID invalidSchemaEventId = UUID.randomUUID();
		UUID invalidSchemaAggregateId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
				                           schema_version, payload, occurred_at)
				VALUES (?, ?, 'KITCHEN_ORDER', ?, 1, 'Other', 0, '{}'::jsonb, now())
				""", invalidSchemaEventId, restaurant, invalidSchemaAggregateId));

		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ?", Integer.class, restaurant),
				"the ready event has one delivery per interested service; the refused rows added none");
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant));
	}

	private void insertDelivery(UUID restaurant, UUID eventId, String destination, String status, String nextAttemptSql, String deliveredSql) {
		jdbc.update("INSERT INTO outbox_deliveries (id, restaurant_id, event_id, destination, status, next_attempt_at, delivered_at) "
				+ "VALUES (?, ?, ?, ?, ?, "
				+ (nextAttemptSql == null ? "NULL" : nextAttemptSql) + ", " + (deliveredSql == null ? "NULL" : deliveredSql) + ")",
				UUID.randomUUID(), restaurant, eventId, destination, status);
	}

}
