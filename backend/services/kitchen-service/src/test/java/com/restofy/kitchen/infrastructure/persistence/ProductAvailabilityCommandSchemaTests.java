package com.restofy.kitchen.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL: the V84 constraints that protect the command record even if the application is wrong. */
@SpringBootTest
class ProductAvailabilityCommandSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private void insert(UUID restaurant, UUID command, String target, String reasonCode, String outcome, int attempts,
			String confirmedAt) {
		jdbc.update("""
				INSERT INTO product_availability_commands (id, restaurant_id, command_id, product_id, target_status, reason_code,
				    requested_by_user_id, requested_at, outcome, attempt_count, confirmed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, CAST(? AS timestamptz))
				""", UUID.randomUUID(), restaurant, command, UUID.randomUUID(), target, reasonCode, UUID.randomUUID(), outcome,
				attempts, confirmedAt);
	}

	private void valid(UUID restaurant, UUID command) {
		insert(restaurant, command, "OUT_OF_STOCK", "OUT_OF_STOCK", "UNCONFIRMED", 0, null);
	}

	private void assertInvalidInsert(UUID restaurant, String target, String reasonCode, String outcome, int attempts,
			String confirmedAt) {
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insert(restaurant, command, target, reasonCode, outcome, attempts, confirmedAt));
	}

	private void assertValidInsert(UUID restaurant, String target, String outcome, String confirmedAt) {
		UUID command = UUID.randomUUID();
		assertDoesNotThrow(() -> insert(restaurant, command, target, "X1", outcome, 1, confirmedAt));
	}

	private void insertWithNullActor(UUID restaurant, UUID command, UUID product) {
		jdbc.update("""
				INSERT INTO product_availability_commands (id, restaurant_id, command_id, product_id, target_status, reason_code,
				    requested_by_user_id, requested_at, outcome)
				VALUES (?, ?, ?, ?, 'OUT_OF_STOCK', 'X1', NULL, now(), 'UNCONFIRMED')
				""", UUID.randomUUID(), restaurant, command, product);
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
	void statusOutcomeAttemptsAndReasonAreChecked() {
		UUID restaurant = UUID.randomUUID();

		assertInvalidInsert(restaurant, "DISCONTINUED", "X1", "UNCONFIRMED", 0, null);
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "X1", "DONE", 0, null);
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "X1", "UNCONFIRMED", -1, null);
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "   ", "UNCONFIRMED", 0, null);
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "X1", "UNCONFIRMED", 0, "2026-10-20T09:00:00Z");
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "X1", "CONFIRMED", 1, null);
		assertValidInsert(restaurant, "TEMPORARILY_CLOSED", "CONFIRMED", "2026-10-20T09:00:00Z");
		assertValidInsert(restaurant, "AVAILABLE", "REJECTED", null);
	}

	@Test
	void aReasonCodeIsOptionalButNeverBlank() {
		UUID restaurant = UUID.randomUUID();
		UUID command = UUID.randomUUID();

		assertDoesNotThrow(() -> insert(restaurant, command, "OUT_OF_STOCK", null, "UNCONFIRMED", 0, null));
		assertDoesNotThrow(() -> insert(restaurant, UUID.randomUUID(), "TEMPORARILY_CLOSED", null, "UNCONFIRMED", 0, null));
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "", "UNCONFIRMED", 0, null);
		assertInvalidInsert(restaurant, "OUT_OF_STOCK", "   ", "UNCONFIRMED", 0, null);
		// Nothing was invented for the missing reason.
		assertNull(jdbc.queryForObject("SELECT reason_code FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?",
				String.class, restaurant, command));
	}

	@Test
	void requiredColumnsCannotBeNull() {
		UUID restaurant = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> insertWithNullActor(restaurant, command, product));
	}
}
