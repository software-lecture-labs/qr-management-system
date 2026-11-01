package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Atomicity of PREPARING -> READY: if the history row cannot be written, status and readyAt must not survive. */
@SpringBootTest
class KitchenMarkReadyRollbackTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private KitchenStatusHistoryRepository history;

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	@Test
	void aFailingHistoryWriteRollsTheReadyTransitionBack() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		Map<String, Object> preparing = state(restaurant, orderId);
		assertEquals("PREPARING", preparing.get("status"));

		doThrow(new DataIntegrityViolationException("simulated history failure"))
				.when(history).saveAndFlush(any(KitchenStatusHistory.class));
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> preparation.markReady(restaurant, actor, command, orderId));

		assertEquals(preparing, state(restaurant, orderId), "status, readyAt, version and history must all be rolled back");
	}
}
