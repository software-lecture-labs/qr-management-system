package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.api.dto.response.DailyKitchenReportResponse;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.DailyReportService;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.support.FakeNotificationSink;
import com.restofy.kitchen.support.FakeOrderEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-04 / 4.4: one restaurant, two orders, the whole story through the real API and a real PostgreSQL, with FAKE Order
 * and Notification services. It is a LOCAL SIMULATION: it proves how Kitchen behaves towards the proposed contracts, not that
 * a real Order, Notification or Auth service is connected (none is).
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenEndToEndSimulationTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private DailyReportService reports;

	private void command(UUID restaurant, UUID orderId, String command) throws Exception {
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/" + command).header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isOk());
	}

	private UUID deliveryOf(UUID restaurant, String destination, String eventType) {
		return jdbc.queryForObject("""
				SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND d.destination = ? AND e.event_type = ?
				""", UUID.class, restaurant, destination, eventType);
	}

	@Test
	void aReadyOrderAndACancelledOrderThroughTheWholeSystemWithFakeOrderAndNotificationServices() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID ready = UUID.randomUUID();
		UUID cancelled = UUID.randomUUID();
		ingest.ingest(simple(restaurant, ready));
		ingest.ingest(simple(restaurant, cancelled));
		FakeOrderEventSink order = new FakeOrderEventSink();
		FakeNotificationSink notification = new FakeNotificationSink();

		// Order 1: prepared and finished. The ready event goes to BOTH services, as the same event.
		command(restaurant, ready, "start-preparation");
		command(restaurant, ready, "mark-ready");
		delivery.attempt(deliveryOf(restaurant, "ORDER", "KitchenOrderReady"), order);
		delivery.attempt(deliveryOf(restaurant, "NOTIFICATION", "KitchenOrderReady"), notification);
		assertEquals(order.received().getFirst(), notification.received().getFirst());
		assertInstanceOf(KitchenOrderReadyMessage.class, notification.received().getFirst());

		// Order 2: the kitchen asks to cancel it while it is being prepared. Nothing is cancelled by asking.
		command(restaurant, cancelled, "start-preparation");
		String requestId = JsonPath.read(mvc.perform(post("/api/v1/kitchen/orders/" + cancelled + "/cancellation-requests")
				.header(KEY, UUID.randomUUID().toString()).contentType("application/json").content("{\"reasonCode\":\"KITCHEN_ERROR\"}")
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REQUESTED"))
				.andReturn().getResponse().getContentAsString(), "$.requestId");
		// The request goes to the Order service only; its "received" is not its decision.
		delivery.attempt(deliveryOf(restaurant, "ORDER", "KitchenCancellationRequested"), order);
		assertInstanceOf(KitchenCancellationRequestedMessage.class, order.received().getLast());
		assertEquals(1, notification.received().size(), "Notification was told about the ready order only");
		assertEquals("PREPARING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE order_id = ?", String.class, cancelled));

		// The fake Order service approves: only now is the order cancelled.
		assertEquals(DecisionOutcome.CANCELLED,
				decisions.apply(SOURCE_ORDER, approved(restaurant, cancelled, UUID.fromString(requestId), 2).message()).outcome());
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE order_id = ?", String.class, cancelled));
		assertEquals("READY", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE order_id = ?", String.class, ready));

		// The day report counts what really happened, and nothing else.
		LocalDate day = jdbc.queryForObject("SELECT (received_at AT TIME ZONE 'UTC')::date FROM kitchen_orders WHERE order_id = ?",
				Date.class, ready).toLocalDate();
		DailyKitchenReportResponse report = reports.daily(restaurant, day, ZoneOffset.UTC);
		assertEquals(2, report.orders().received());
		assertEquals(1, report.orders().currentStatusOfReceived().ready());
		assertEquals(1, report.orders().currentStatusOfReceived().cancelled());
		assertEquals(1, report.orders().duringDay().becameReady());
		assertEquals(1, report.orders().duringDay().cancelled());

		// Every message that left Kitchen went through a fake: nothing real was contacted.
		List<String> destinations = jdbc.queryForList("SELECT destination || ':' || status FROM outbox_deliveries WHERE restaurant_id = "
				+ "? ORDER BY 1",
				String.class, restaurant);
		assertEquals(List.of("NOTIFICATION:DELIVERED", "ORDER:DELIVERED", "ORDER:DELIVERED"), destinations,
				"the ready event reached both fakes, the cancellation request reached the Order fake only, and the cancelled order never became ready");
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE restaurant_id = ?", Integer.class, restaurant));
	}
}
