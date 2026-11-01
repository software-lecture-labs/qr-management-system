package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
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

/** Atomicity (K2-A14): the READY transition and its event commit together or not at all. */
@SpringBootTest
class KitchenReadyEventRollbackTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private OutboxDeliveryRepository deliveries;

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e WHERE e.restaurant_id = o.restaurant_id) AS events,
				       (SELECT count(*) FROM outbox_deliveries d WHERE d.restaurant_id = o.restaurant_id) AS deliveries
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	@Test
	void ifTheEventCannotBeRecordedTheReadyTransitionIsRolledBackCompletely() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		Map<String, Object> preparing = state(restaurant, orderId);
		assertEquals("PREPARING", preparing.get("status"));

		doThrow(new DataIntegrityViolationException("simulated outbox failure")).when(deliveries).saveAndFlush(any(OutboxDelivery.class));
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> preparation.markReady(restaurant, actor, command, orderId));

		// status, readyAt, version, history, the already-flushed event row and the delivery: all rolled back together.
		assertEquals(preparing, state(restaurant, orderId));
		assertEquals(0L, preparing.get("events"));
		assertEquals(0L, preparing.get("deliveries"));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM kitchen_status_history WHERE restaurant_id = ? AND command_id = ?",
				Integer.class, restaurant, command), "the command id is free to be used again");
	}
}
