package com.restofy.kitchen.application.service;

import com.restofy.kitchen.KitchenServiceApplication;
import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A cancellation request and its pending event survive a restart; the restart decides nothing and sends nothing. */
@SpringBootTest
class CancellationRequestRestartPersistenceTests {

	@Autowired
	private JdbcTemplate jdbc;

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(KitchenServiceApplication.class).profiles("test").properties("server.port=0").run();
	}

	@Test
	void theRequestItsEventAndTheOrderStateSurviveARestartAndARepeatIsStillARepeat() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		var body = new RequestOrderCancellationRequest(CancellationReason.CUSTOMER_REQUEST, "vazgeçti");
		UUID requestId;
		try (ConfigurableApplicationContext first = start()) {
			first.getBean(AcceptedOrderIngestService.class).ingest(simple(restaurant, orderId));
			requestId = first.getBean(CancellationRequestService.class).request(restaurant, actor, key, orderId, body).request().getId();
		}

		try (ConfigurableApplicationContext second = start()) {
			var repeat = second.getBean(CancellationRequestService.class).request(restaurant, actor, key, orderId, body);

			assertFalse(repeat.created(), "recognised as the same command after the restart");
			assertEquals(requestId, repeat.request().getId());
			assertEquals(CancellationRequestStatus.REQUESTED, repeat.request().getStatus());
		}

		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = "
				+ "'KitchenCancellationRequested'",
				Integer.class, restaurant));
		assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE restaurant_id = ?", String.class, restaurant),
				"nothing was sent by the restart");
		assertEquals("WAITING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
		assertTrue(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM kitchen_orders WHERE restaurant_id = ?", Boolean.class, restaurant));
	}
}
