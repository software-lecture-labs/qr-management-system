package com.restofy.kitchen.application.service;

import com.restofy.kitchen.KitchenServiceApplication;
import com.restofy.kitchen.api.dto.response.KitchenOrderDetailResponse;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.KitchenStatusFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * "Restart" proof for the preparation lifecycle: one whole application context starts, prepares and finishes an
 * order and is closed; a new context against the same database must show READY, both times and the history.
 * (Application restart only; the PostgreSQL container itself is not restarted.)
 */
@SpringBootTest
class KitchenPreparationRestartPersistenceTests {

	@Autowired
	private JdbcTemplate jdbc;

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(KitchenServiceApplication.class).profiles("test").properties("server.port=0").run();
	}

	@Test
	void readyOrderTimesAndHistorySurviveAnApplicationRestart() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID readyKey = UUID.randomUUID();
		KitchenOrderDetailResponse finished;
		try (ConfigurableApplicationContext first = start()) {
			first.getBean(AcceptedOrderIngestService.class).ingest(
					message(restaurant, orderId, UUID.randomUUID(), "9", "A-9", 1, UUID.randomUUID(), item("Burger", 2, "not")));
			KitchenPreparationService service = first.getBean(KitchenPreparationService.class);
			service.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
			finished = service.markReady(restaurant, actor, readyKey, orderId);
		}
		assertEquals(KitchenOrderStatus.READY, finished.status());

		try (ConfigurableApplicationContext second = start()) {
			KitchenOrderQueryService queries = second.getBean(KitchenOrderQueryService.class);
			KitchenOrderDetailResponse detail = queries.get(restaurant, orderId);
			assertEquals(KitchenOrderStatus.READY, detail.status());
			assertEquals(finished.startedAt(), detail.startedAt());
			assertEquals(finished.readyAt(), detail.readyAt());
			assertNotNull(detail.startedAt());
			assertFalse(detail.readyAt().isBefore(detail.startedAt()));
			assertEquals("not", detail.items().get(0).note());

			var ready = queries.list(restaurant, KitchenStatusFilter.READY, null, null, 0, 20);
			assertEquals(1, ready.totalElements());
			assertEquals(orderId, ready.content().get(0).orderId());
			assertEquals(0, queries.list(restaurant, KitchenStatusFilter.ACTIVE, null, null, 0, 20).totalElements());

			// The same command repeated after the restart is still recognised as a repeat: 200 and no change.
			Map<String, Object> before = jdbc.queryForMap(
					"SELECT version, ready_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
			KitchenOrderDetailResponse repeat = second.getBean(KitchenPreparationService.class).markReady(restaurant, actor, readyKey, orderId);
			assertEquals(KitchenOrderStatus.READY, repeat.status());
			assertEquals(before, jdbc.queryForMap(
					"SELECT version, ready_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId));
		}
		assertEquals(3L, jdbc.queryForObject("""
				SELECT count(*) FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ?
				""", Long.class, restaurant, orderId));
	}
}
