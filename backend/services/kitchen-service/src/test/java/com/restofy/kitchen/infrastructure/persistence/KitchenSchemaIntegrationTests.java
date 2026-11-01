package com.restofy.kitchen.infrastructure.persistence;

import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL (Testcontainers): Flyway V81 + Hibernate validate + constraint behaviour. */
@SpringBootTest
class KitchenSchemaIntegrationTests {

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private KitchenOrderRepository orders;
	@Autowired
	private TransactionTemplate tx;

	private UUID insertOrder(UUID restaurantId, UUID orderId, String status, String startedAt, String readyAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot,
				    order_number_snapshot, status, received_at, started_at, ready_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', ?, now() - interval '1 hour',
				    CAST(? AS timestamptz), CAST(? AS timestamptz), 1, ?)
				""", id, restaurantId, orderId, UUID.randomUUID(), status, startedAt, readyAt, UUID.randomUUID());
		return id;
	}

	private void insertItem(UUID restaurantId, UUID kitchenOrderId, int line, int quantity, int cancelled, String options) {
		jdbc.update("""
				INSERT INTO kitchen_order_items (id, restaurant_id, kitchen_order_id, line_number, order_item_id,
				    product_id, product_name_snapshot, quantity, cancelled_quantity, options_snapshot)
				VALUES (?, ?, ?, ?, ?, ?, 'Burger', ?, ?, CAST(? AS jsonb))
				""", UUID.randomUUID(), restaurantId, kitchenOrderId, line, UUID.randomUUID(), UUID.randomUUID(),
				quantity, cancelled, options);
	}

	@Test
	void flywayAppliedTheKitchenMigrationsInOrderAndHibernateValidatedTheSchema() {
		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
		assertEquals(List.of("81", "82", "83", "84", "85", "86", "87", "88", "89"), versions);
		assertEquals(3, jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'
				  AND table_name IN ('kitchen_orders', 'kitchen_order_items', 'kitchen_status_history')
				""", Integer.class));
	}

	private void insertHistory(UUID restaurantId, UUID kitchenOrderId, String toStatus, UUID commandId) {
		jdbc.update("""
				INSERT INTO kitchen_status_history (id, restaurant_id, kitchen_order_id, to_status, command_id, occurred_at)
				VALUES (?, ?, ?, ?, ?, now())
				""", UUID.randomUUID(), restaurantId, kitchenOrderId, toStatus, commandId);
	}

	@Test
	void eachStatusCanBeEnteredOnlyOncePerOrder() {
		UUID restaurant = UUID.randomUUID();
		UUID kitchenOrder = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);
		insertHistory(restaurant, kitchenOrder, "WAITING", null);
		insertHistory(restaurant, kitchenOrder, "PREPARING", UUID.randomUUID());

		UUID duplicateCommand = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> insertHistory(restaurant, kitchenOrder, "PREPARING", duplicateCommand));
		assertThrows(DataIntegrityViolationException.class, () -> insertHistory(restaurant, kitchenOrder, "WAITING", null));
		// Another order of the same restaurant may enter the same statuses.
		UUID other = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);
		insertHistory(restaurant, other, "WAITING", null);
	}

	@Test
	void aCommandIdIsUniquePerRestaurantButNullsAndOtherRestaurantsAreFine() {
		UUID restaurant = UUID.randomUUID();
		UUID first = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);
		UUID second = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);
		UUID command = UUID.randomUUID();
		insertHistory(restaurant, first, "PREPARING", command);

		assertThrows(DataIntegrityViolationException.class, () -> insertHistory(restaurant, second, "PREPARING", command));
		insertHistory(restaurant, first, "WAITING", null);
		insertHistory(restaurant, second, "WAITING", null);
		UUID otherRestaurant = UUID.randomUUID();
		UUID foreign = insertOrder(otherRestaurant, UUID.randomUUID(), "WAITING", null, null);
		insertHistory(otherRestaurant, foreign, "PREPARING", command);
	}

	@Test
	void sameOrderIdIsRejectedWithinATenantButAllowedAcrossTenants() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		insertOrder(restaurant, orderId, "WAITING", null, null);

		assertThrows(DataIntegrityViolationException.class,
				() -> insertOrder(restaurant, orderId, "WAITING", null, null));
		insertOrder(UUID.randomUUID(), orderId, "WAITING", null, null);
	}

	@Test
	void childRowMustBelongToTheSameTenantAsItsParent() {
		UUID restaurant = UUID.randomUUID();
		UUID kitchenOrder = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);

		insertItem(restaurant, kitchenOrder, 1, 2, 0, "{\"schemaVersion\":1,\"options\":[]}");
		assertThrows(DataIntegrityViolationException.class,
				() -> insertItem(UUID.randomUUID(), kitchenOrder, 2, 1, 0, "{\"schemaVersion\":1,\"options\":[]}"));
	}

	@Test
	void itemChecksRejectInvalidQuantitiesAndNonObjectOptions() {
		UUID restaurant = UUID.randomUUID();
		UUID kitchenOrder = insertOrder(restaurant, UUID.randomUUID(), "WAITING", null, null);

		assertThrows(DataIntegrityViolationException.class, () -> insertItem(restaurant, kitchenOrder, 1, 0, 0, "{}"));
		assertThrows(DataIntegrityViolationException.class, () -> insertItem(restaurant, kitchenOrder, 2, 1, 2, "{}"));
		assertThrows(DataIntegrityViolationException.class, () -> insertItem(restaurant, kitchenOrder, 3, 1, 0, "[]"));
	}

	@Test
	void statusAndTimestampChecksProtectTheStateModel() {
		UUID restaurant = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insertOrder(restaurant, UUID.randomUUID(), "DELIVERED", null, null));
		assertThrows(DataIntegrityViolationException.class,
				() -> insertOrder(restaurant, UUID.randomUUID(), "PREPARING", null, null));
		assertThrows(DataIntegrityViolationException.class,
				() -> insertOrder(restaurant, UUID.randomUUID(), "READY", "2026-01-01T10:00:00Z", null));
	}

	@Test
	void entityRoundTripKeepsJsonbSnapshotAndItemOrder() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		String options = "{\"schemaVersion\":1,\"options\":[{\"action\":\"REMOVE\",\"name\":\"Soğan\"}]}";
		tx.executeWithoutResult(status -> {
			KitchenOrder order = KitchenOrder.receive(restaurant, orderId, UUID.randomUUID(), "12", "A-100",
					Instant.parse("2026-10-17T10:00:00Z"), 1, UUID.randomUUID(), Instant.now());
			order.addItem(2, UUID.randomUUID(), UUID.randomUUID(), "Ayran", 1, "{\"schemaVersion\":1,\"options\":[]}", null);
			order.addItem(1, UUID.randomUUID(), UUID.randomUUID(), "Burger", 2, options, "az pişmiş");
			orders.save(order);
		});

		tx.executeWithoutResult(status -> {
			KitchenOrder loaded = orders.findByRestaurantIdAndOrderId(restaurant, orderId).orElseThrow();
			assertEquals(KitchenOrderStatus.WAITING, loaded.getStatus());
			assertEquals(0L, loaded.getVersion());
			assertEquals("Burger", loaded.getItems().get(0).getProductNameSnapshot());
			assertEquals("Ayran", loaded.getItems().get(1).getProductNameSnapshot());
			assertTrue(loaded.getItems().get(0).getOptionsSnapshot().contains("Soğan"));
		});
		assertTrue(orders.findByRestaurantIdAndOrderId(UUID.randomUUID(), orderId).isEmpty());
	}
}
