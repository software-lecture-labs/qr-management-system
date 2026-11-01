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

/** Real PostgreSQL: the V87 constraints that protect cancellation requests even if the application is wrong. */
@SpringBootTest
class CancellationRequestSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private UUID kitchenOrder(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', 'WAITING', now(), 1, ?)
				""", UUID.randomUUID(), restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		return orderId;
	}

	private void insert(UUID restaurant, UUID orderId, UUID command, String status, String reason, String text, UUID product,
			UUID item, boolean decided) {
		jdbc.update("""
				INSERT INTO cancellation_requests (id, restaurant_id, order_id, command_id, status, reason_code, reason_text,
				    product_id, order_item_id, requested_by_user_id, requested_at, decided_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), CASE WHEN ? THEN now() END)
				""", UUID.randomUUID(), restaurant, orderId, command, status, reason, text, product, item, UUID.randomUUID(), decided);
	}

	private void open(UUID restaurant, UUID orderId, UUID command) {
		insert(restaurant, orderId, command, "REQUESTED", "OTHER", null, null, null, false);
	}

	@Test
	void aCommandIdIsUniquePerRestaurantOnly() {
		UUID restaurant = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		open(restaurant, kitchenOrder(restaurant), command);

		UUID secondOrder = kitchenOrder(restaurant);
		assertInvalidOpen(restaurant, secondOrder, command);
		UUID other = UUID.randomUUID();
		UUID otherOrder = kitchenOrder(other);
		assertValidOpen(other, otherOrder, command);
	}

	@Test
	void anOrderHasAtMostOneUndecidedRequestAtATime() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);
		open(restaurant, orderId, UUID.randomUUID());

		assertInvalidOpen(restaurant, orderId, UUID.randomUUID());
		// Decided requests do not count: after a rejection another one may be open, and decided ones may pile up.
		assertValidInsert(restaurant, orderId, "REJECTED", "OTHER", null, null, null, true);
		assertValidInsert(restaurant, orderId, "APPROVED", "OTHER", null, null, null, true);
	}

	@Test
	void theOrderMustBelongToTheSameRestaurant() {
		UUID restaurant = UUID.randomUUID();
		UUID foreign = kitchenOrder(UUID.randomUUID());

		assertInvalidOpen(restaurant, foreign, UUID.randomUUID());
		assertInvalidOpen(restaurant, UUID.randomUUID(), UUID.randomUUID());
	}

	@Test
	void statusReasonTextItemAndDecisionAreChecked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);

		assertInvalidInsert(restaurant, orderId, "CANCELLED", "OTHER", null, null, null, true);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "BORED", null, null, null, false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "", null, null, null, false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OTHER", "   ", null, null, false);
		// An item reference without its product, a decided REQUESTED, and an undecided APPROVED are all inconsistent.
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OUT_OF_STOCK", null, null, UUID.randomUUID(), false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OTHER", null, null, null, true);
		assertInvalidInsert(restaurant, orderId, "APPROVED", "OTHER", null, null, null, false);
		assertValidInsert(restaurant, orderId, "REJECTED", "OUT_OF_STOCK", "not", UUID.randomUUID(), null, true);
	}

	// ---- V88: stock reference rules --------------------------------------------------------------------------------

	private record OrderWithLine(UUID orderId, UUID itemId, UUID productId) {
	}

	private OrderWithLine orderWithLine(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		UUID kitchenId = UUID.randomUUID();
		UUID itemId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', 'WAITING', now(), 1, ?)
				""", kitchenId, restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("""
				INSERT INTO kitchen_order_items (id, restaurant_id, kitchen_order_id, line_number, order_item_id, product_id,
				    product_name_snapshot, quantity, cancelled_quantity, options_snapshot)
				VALUES (?, ?, ?, 1, ?, ?, 'Burger', 1, 0, '{}'::jsonb)
				""", UUID.randomUUID(), restaurant, kitchenId, itemId, productId);
		return new OrderWithLine(orderId, itemId, productId);
	}

	@Test
	void aLineReferenceMustBeARealLineOfTheSameRestaurantAndOnlyAStockRequestMayCarryOne() {
		UUID restaurant = UUID.randomUUID();
		OrderWithLine mine = orderWithLine(restaurant);
		OrderWithLine foreign = orderWithLine(UUID.randomUUID());

		assertValidInsert(restaurant, mine.orderId(), "REJECTED", "OUT_OF_STOCK", null, mine.productId(), mine.itemId(), true);
		assertInvalidInsert(restaurant, mine.orderId(), "REJECTED", "OUT_OF_STOCK", null, mine.productId(), UUID.randomUUID(), true);
		assertInvalidInsert(restaurant, mine.orderId(), "REJECTED", "OUT_OF_STOCK", null, foreign.productId(), foreign.itemId(), true);
		// Any other reason carries no product reference at all.
		assertInvalidInsert(restaurant, mine.orderId(), "REJECTED", "OTHER", null, mine.productId(), null, true);
		assertInvalidInsert(restaurant, mine.orderId(), "REJECTED", "KITCHEN_ERROR", null, mine.productId(), mine.itemId(), true);
		assertValidInsert(restaurant, mine.orderId(), "REJECTED", "OTHER", null, null, null, true);
	}

	// ---- V88: the audit trail is immutable --------------------------------------------------------------------------

	private UUID openRequest(UUID restaurant, UUID orderId) {
		UUID command = UUID.randomUUID();
		open(restaurant, orderId, command);
		return jdbc.queryForObject("SELECT id FROM cancellation_requests WHERE restaurant_id = ? AND command_id = ?", UUID.class, restaurant,
				command);
	}

	@Test
	void whatTheKitchenAskedForCanNeverBeChanged() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);
		UUID id = openRequest(restaurant, orderId);

		for (String change : new String[] { "reason_code = 'KITCHEN_ERROR'", "reason_text = 'başka'", "requested_by_user_id = gen_random_uuid()",
				"requested_at = now() + interval '1 hour'", "command_id = gen_random_uuid()", "order_id = gen_random_uuid()",
				"restaurant_id = gen_random_uuid()", "id = gen_random_uuid()", "product_id = gen_random_uuid()" }) {
			assertThrows(DataIntegrityViolationException.class,
					() -> jdbc.update("UPDATE cancellation_requests SET " + change + " WHERE id = ?", id), change);
		}
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM cancellation_requests WHERE id = ?", id));
		// Nothing was lost by the refused attempts.
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE id = ? AND reason_code = 'OTHER' AND "
				+ "status = 'REQUESTED'",
				Integer.class, id));
	}

	@Test
	void aRequestCanBeDecidedExactlyOnceAndADecidedRequestIsFinal() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);
		UUID id = openRequest(restaurant, orderId);

		assertDoesNotThrow(() -> jdbc.update("""
				UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now(), decision_reason_code = 'ALREADY_SERVED',
				    decision_order_version = 7, decision_event_id = gen_random_uuid(), version = version + 1 WHERE id = ?
				""", id));

		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE cancellation_requests SET status = 'APPROVED' WHERE id = ?", id));
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE cancellation_requests SET decision_reason_code = 'X' WHERE id = ?", id));
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE cancellation_requests SET decided_at = now() WHERE id = ?", id));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM cancellation_requests WHERE id = ?", id));
		assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM cancellation_requests WHERE id = ?", String.class, id));
		assertEquals("ALREADY_SERVED",
				jdbc.queryForObject("SELECT decision_reason_code FROM cancellation_requests WHERE id = ?", String.class, id));
	}

	@Test
	void anApprovedDecisionIsFinalToo() {
		UUID restaurant = UUID.randomUUID();
		UUID id = openRequest(restaurant, kitchenOrder(restaurant));

		assertDoesNotThrow(() -> jdbc.update("UPDATE cancellation_requests SET status = 'APPROVED', decided_at = now() WHERE id = ?", id));

		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED' WHERE id = ?", id));
	}

	private void assertInvalidOpen(UUID restaurant, UUID orderId, UUID command) {
		assertThrows(DataIntegrityViolationException.class, () -> open(restaurant, orderId, command));
	}

	private void assertValidOpen(UUID restaurant, UUID orderId, UUID command) {
		assertDoesNotThrow(() -> open(restaurant, orderId, command));
	}

	private void assertInvalidInsert(UUID restaurant, UUID orderId, String status, String reason, String text,
			UUID product, UUID item, boolean decided) {
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insert(restaurant, orderId, command, status, reason, text, product, item, decided));
	}

	private void assertValidInsert(UUID restaurant, UUID orderId, String status, String reason, String text,
			UUID product, UUID item, boolean decided) {
		UUID command = UUID.randomUUID();
		assertDoesNotThrow(() -> insert(restaurant, orderId, command, status, reason, text, product, item, decided));
	}
}
