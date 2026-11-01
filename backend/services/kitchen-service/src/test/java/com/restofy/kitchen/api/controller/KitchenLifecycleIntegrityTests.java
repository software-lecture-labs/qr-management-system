package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data integrity of the whole lifecycle on PostgreSQL: after the valid sequence the stored times, versions and
 * history form one consistent chain, and the database itself refuses the illegal shortcuts even if the application
 * code were bypassed. (There is no event/outbox table yet; it is added to this check in task 2.4.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenLifecycleIntegrityTests {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private void command(UUID restaurant, UUID actor, UUID orderId, String command) throws Exception {
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/" + command).with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
	}

	@Test
	void theValidSequenceLeavesOneConsistentChainOfTimesVersionsAndHistory() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 2, "not")));

		command(restaurant, actor, orderId, "start-preparation");
		command(restaurant, actor, orderId, "mark-ready");

		Map<String, Object> order = jdbc.queryForMap("""
				SELECT id, status, received_at, started_at, ready_at, cancelled_at, version, created_at, updated_at
				FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?
				""", restaurant, orderId);
		Timestamp received = (Timestamp) order.get("received_at");
		Timestamp started = (Timestamp) order.get("started_at");
		Timestamp ready = (Timestamp) order.get("ready_at");
		assertEquals("READY", order.get("status"));
		assertFalse(started.before(received), "received <= started");
		assertFalse(ready.before(started), "started <= ready");
		assertNull(order.get("cancelled_at"));
		assertEquals(2L, order.get("version"), "one version per applied transition");
		assertFalse(((Timestamp) order.get("updated_at")).before((Timestamp) order.get("created_at")));

		List<Map<String, Object>> history = jdbc.queryForList("""
				SELECT from_status, to_status, actor_user_id, command_id, occurred_at
				FROM kitchen_status_history WHERE kitchen_order_id = ? ORDER BY occurred_at, to_status
				""", order.get("id"));
		assertEquals(3, history.size(), "WAITING at receipt, PREPARING, READY - no more, no less");
		assertEquals(List.of("WAITING", "PREPARING", "READY"), history.stream().map(h -> (String) h.get("to_status")).toList());
		// Each entry starts where the previous one ended.
		assertNull(history.get(0).get("from_status"));
		assertEquals("WAITING", history.get(1).get("from_status"));
		assertEquals("PREPARING", history.get(2).get("from_status"));
		// System entry has no actor/command; the two user commands carry both, with distinct command ids.
		assertNull(history.get(0).get("actor_user_id"));
		assertNull(history.get(0).get("command_id"));
		assertEquals(actor, history.get(1).get("actor_user_id"));
		assertEquals(actor, history.get(2).get("actor_user_id"));
		assertTrue(history.get(1).get("command_id") != null && history.get(2).get("command_id") != null);
		assertNotEquals(history.get(1).get("command_id"), history.get(2).get("command_id"));
		// The history times are the order's own times.
		assertEquals(started, history.get(1).get("occurred_at"));
		assertEquals(ready, history.get(2).get("occurred_at"));
	}

	@Test
	void theDatabaseItselfRefusesTheShortcutsEvenIfTheApplicationWereBypassed() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		// WAITING -> READY without a start time.
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE kitchen_orders SET status = 'READY', ready_at = now() WHERE restaurant_id = ? AND order_id = ?",
				restaurant, orderId));
		// A start time before the order was received.
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE kitchen_orders SET status = 'PREPARING', started_at = received_at - interval '1 minute' "
						+ "WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId));
		// READY before it was started.
		command(restaurant, UUID.randomUUID(), orderId, "start-preparation");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE kitchen_orders SET status = 'READY', ready_at = started_at - interval '1 second' "
						+ "WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId));
		// A second PREPARING history entry for the same order.
		UUID kitchenId = jdbc.queryForObject("SELECT id FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				UUID.class, restaurant, orderId);
		UUID duplicateHistoryId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO kitchen_status_history (id, restaurant_id, kitchen_order_id, from_status, to_status, occurred_at)
				VALUES (?, ?, ?, 'WAITING', 'PREPARING', now())
				""", duplicateHistoryId, restaurant, kitchenId));
		// A history entry for another restaurant's order (tenant mix-up through the composite foreign key).
		UUID crossTenantHistoryId = UUID.randomUUID();
		UUID otherRestaurant = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO kitchen_status_history (id, restaurant_id, kitchen_order_id, to_status, occurred_at)
				VALUES (?, ?, ?, 'READY', now())
				""", crossTenantHistoryId, otherRestaurant, kitchenId));

		Map<String, Object> order = jdbc.queryForMap(
				"SELECT status, ready_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
		assertEquals("PREPARING", order.get("status"));
		assertNull(order.get("ready_at"), "every refused statement left the order exactly as it was");
	}
}
