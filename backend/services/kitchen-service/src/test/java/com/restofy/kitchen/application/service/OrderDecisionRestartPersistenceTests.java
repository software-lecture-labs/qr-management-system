package com.restofy.kitchen.application.service;

import com.restofy.kitchen.KitchenServiceApplication;
import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.service.OrderDecisionService.DecisionResult;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The decision, the cancelled order and the inbox survive a restart, and a restart neither decides a request nor repeats a
 * decision. An undecided request stays undecided (nothing times out into a cancellation).
 */
@SpringBootTest
class OrderDecisionRestartPersistenceTests {

	@Autowired
	private JdbcTemplate jdbc;

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(KitchenServiceApplication.class).profiles("test").properties("server.port=0").run();
	}

	@Test
	void aRestartKeepsTheCancellationAndStillRecognisesTheSameDecisionAsARepeat() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID requestId;
		var draftHolder = new com.restofy.kitchen.support.FakeOrderDecisionSource.Draft[1];
		try (ConfigurableApplicationContext first = start()) {
			first.getBean(AcceptedOrderIngestService.class).ingest(simple(restaurant, orderId));
			requestId = first.getBean(CancellationRequestService.class).request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
					new RequestOrderCancellationRequest(CancellationReason.CUSTOMER_REQUEST, null)).request().getId();
			draftHolder[0] = approved(restaurant, orderId, requestId, 2);
			assertEquals(DecisionOutcome.CANCELLED,
					first.getBean(OrderDecisionService.class).apply(SOURCE_ORDER, draftHolder[0].message()).outcome());
		}

		try (ConfigurableApplicationContext second = start()) {
			DecisionResult repeat = second.getBean(OrderDecisionService.class).apply(SOURCE_ORDER, draftHolder[0].message());

			assertEquals(new DecisionResult(DecisionOutcome.CANCELLED, true), repeat);
		}
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
		assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM cancellation_requests WHERE id = ?", String.class, requestId));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals(2, jdbc.queryForObject("""
				SELECT count(*) FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id WHERE o.restaurant_id = ?
				""", Integer.class, restaurant), "WAITING at the start and one CANCELLED, never two");
	}

	@Test
	void aRestartDoesNotDecideAnUndecidedRequestAndTheDecisionCanStillArriveAfterwards() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID requestId;
		try (ConfigurableApplicationContext first = start()) {
			first.getBean(AcceptedOrderIngestService.class).ingest(simple(restaurant, orderId));
			requestId = first.getBean(CancellationRequestService.class).request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
					new RequestOrderCancellationRequest(CancellationReason.OTHER, null)).request().getId();
		}

		try (ConfigurableApplicationContext second = start()) {
			assertEquals("WAITING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
			assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM cancellation_requests WHERE id = ?", String.class, requestId));
			assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE restaurant_id = ?", Integer.class, restaurant));

			assertEquals(DecisionOutcome.CANCELLED,
					second.getBean(OrderDecisionService.class).apply(SOURCE_ORDER, approved(restaurant, orderId, requestId, 2).message()).outcome());
		}
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
	}
}
