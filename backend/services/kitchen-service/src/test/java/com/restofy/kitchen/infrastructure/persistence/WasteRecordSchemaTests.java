package com.restofy.kitchen.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL: the V86 constraints that protect the waste records even if the application is wrong. */
@SpringBootTest
class WasteRecordSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private void insert(UUID restaurant, UUID command, int quantity, String reason, String description, String name, UUID orderId) {
		jdbc.update("""
				INSERT INTO waste_records (id, restaurant_id, command_id, product_id, product_name_snapshot, quantity, reason_code,
				    description, order_id, recorded_at, recorded_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
				""", UUID.randomUUID(), restaurant, command, UUID.randomUUID(), name, quantity, reason, description, orderId, UUID.randomUUID());
	}

	private void valid(UUID restaurant, UUID command) {
		insert(restaurant, command, 1, "OTHER", null, null, null);
	}

	private void assertInvalidInsert(UUID restaurant, int quantity, String reason, String description, String name,
			UUID orderId) {
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insert(restaurant, command, quantity, reason, description, name, orderId));
	}

	private void assertValidInsert(UUID restaurant, int quantity, String reason, String description, String name,
			UUID orderId) {
		UUID command = UUID.randomUUID();
		assertDoesNotThrow(() -> insert(restaurant, command, quantity, reason, description, name, orderId));
	}

	private UUID kitchenOrder(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', 'WAITING', now(), 1, ?)
				""", UUID.randomUUID(), restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		return orderId;
	}

	@Test
	void aCommandIdIsUniquePerRestaurantOnly() {
		UUID restaurant = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		valid(restaurant, command);

		assertThrows(DataIntegrityViolationException.class, () -> valid(restaurant, command));
		assertDoesNotThrow(() -> valid(UUID.randomUUID(), command));
		assertDoesNotThrow(() -> valid(restaurant, UUID.randomUUID()));
	}

	@Test
	void quantityReasonAndTextsAreChecked() {
		UUID restaurant = UUID.randomUUID();

		for (int bad : new int[] { 0, -1, 10_001 }) {
			assertInvalidInsert(restaurant, bad, "OTHER", null, null, null);
		}
		assertValidInsert(restaurant, 1, "OTHER", null, null, null);
		assertValidInsert(restaurant, 10_000, "OTHER", null, null, null);
		for (String reason : new String[] { "BURNT", "spoilage", "", "OTHER " }) {
			assertInvalidInsert(restaurant, 1, reason, null, null, null);
		}
		for (String reason : new String[] { "WRONG_PREPARATION", "SPOILAGE", "DROPPED_OR_DAMAGED", "WASTE_AFTER_CANCELLATION", "OTHER" }) {
			assertValidInsert(restaurant, 1, reason, null, null, null);
		}
		assertInvalidInsert(restaurant, 1, "OTHER", "   ", null, null);
		assertInvalidInsert(restaurant, 1, "OTHER", null, "  ", null);
		assertInvalidInsert(restaurant, 1, "OTHER", "x".repeat(501), null, null);
		assertValidInsert(restaurant, 1, "OTHER", "Bozuldu", "Köfte", null);
	}

	@Test
	void anOrderLinkMustBelongToTheSameRestaurantAndIsOptional() {
		UUID restaurant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID mine = kitchenOrder(restaurant);
		UUID foreign = kitchenOrder(other);

		assertValidInsert(restaurant, 1, "OTHER", null, null, mine);
		assertValidInsert(restaurant, 1, "OTHER", null, null, null);
		assertInvalidInsert(restaurant, 1, "OTHER", null, null, foreign);
		UUID missingOrder = UUID.randomUUID();
		assertInvalidInsert(restaurant, 1, "OTHER", null, null, missingOrder);
	}
}
