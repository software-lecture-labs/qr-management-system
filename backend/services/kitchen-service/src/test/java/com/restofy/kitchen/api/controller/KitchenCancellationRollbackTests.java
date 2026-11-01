package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.CancellationRequestService;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Atomicity: a cancellation request and its outbox event commit together or not at all. */
@SpringBootTest
class KitchenCancellationRollbackTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private OutboxDeliveryRepository deliveries;

	@Test
	void ifTheEventCannotBeRecordedTheRequestIsRolledBackCompletelyAndTheKeyIsFreeAgain() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		Map<String, Object> before = jdbc.queryForMap(
				"SELECT status, version FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);

		doThrow(new DataIntegrityViolationException("simulated outbox failure")).when(deliveries).saveAndFlush(any(OutboxDelivery.class));
		var body = new RequestOrderCancellationRequest(CancellationReason.OTHER, null);
		assertThrows(DataIntegrityViolationException.class, () -> cancellations.request(restaurant, actor, key, orderId, body));

		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?", Integer.class, restaurant),
				"no request without its event");
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals(before, jdbc.queryForMap("SELECT status, version FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				restaurant, orderId));
	}
}
