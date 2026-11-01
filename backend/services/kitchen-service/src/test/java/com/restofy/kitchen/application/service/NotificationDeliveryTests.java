package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.application.port.NotificationEventSink;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.support.FakeNotificationSink;
import com.restofy.kitchen.support.FakeOrderEventSink;
import com.restofy.kitchen.support.FakeOrderEventSink.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KITCHEN-04 / 4.4: the order-ready event goes to the Order AND the Notification service as ONE event with two independent
 * deliveries. Each is acknowledged, retried and given up on by itself; a problem at one never touches the other, the order,
 * or an Order decision. This proves Kitchen's behaviour towards the PROPOSED contract with fake services, not a delivery to
 * a real Notification service (none is connected).
 */
@SpringBootTest
class NotificationDeliveryTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private ApplicationContext context;
	@Autowired
	private JdbcTemplate jdbc;

	private record Ready(UUID restaurant, UUID orderId, UUID eventId, UUID orderDelivery, UUID notificationDelivery) {
	}

	private Ready readyOrder() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);
		UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE restaurant_id = ?", UUID.class, restaurant);
		return new Ready(restaurant, orderId, eventId, deliveryOf(restaurant, "ORDER"), deliveryOf(restaurant, "NOTIFICATION"));
	}

	private UUID deliveryOf(UUID restaurant, String destination) {
		return jdbc.queryForObject("SELECT id FROM outbox_deliveries WHERE restaurant_id = ? AND destination = ?", UUID.class, restaurant,
				destination);
	}

	private void makeDue(UUID deliveryId) {
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ? AND status = 'PENDING'", deliveryId);
	}

	private Map<String, Object> row(UUID deliveryId) {
		return jdbc.queryForMap("SELECT status, attempt_count, delivered_at, last_error_code FROM outbox_deliveries WHERE id = ?", deliveryId);
	}

	private String orderStatus(Ready ready) {
		return jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", String.class,
				ready.restaurant(), ready.orderId());
	}

	@Test
	void oneReadyEventHasExactlyTwoDeliveriesOneForEachInterestedService() {
		Ready ready = readyOrder();

		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, ready.restaurant()),
				"never a second event");
		assertEquals(List.of("NOTIFICATION", "ORDER"), jdbc.queryForList(
				"SELECT destination FROM outbox_deliveries WHERE restaurant_id = ? AND event_id = ? ORDER BY destination", String.class,
						ready.restaurant(), ready.eventId()));
		assertEquals("PENDING", row(ready.orderDelivery()).get("status"));
		assertEquals("PENDING", row(ready.notificationDelivery()).get("status"));
		assertEquals(0, row(ready.notificationDelivery()).get("attempt_count"), "nothing was sent: there is no real Notification service");
	}

	@Test
	void bothServicesReceiveTheSameStableEventAndAcknowledgeIndependently() {
		Ready ready = readyOrder();
		FakeOrderEventSink order = new FakeOrderEventSink().mode(Mode.UNAVAILABLE);
		FakeNotificationSink notification = new FakeNotificationSink();

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(ready.orderDelivery(), order));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(ready.notificationDelivery(), notification));

		assertEquals("PENDING", row(ready.orderDelivery()).get("status"), "the Order service is still to be told");
		assertEquals("DELIVERED", row(ready.notificationDelivery()).get("status"));
		KitchenOrderReadyMessage toOrder = assertInstanceOf(KitchenOrderReadyMessage.class, order.received().getFirst());
		KitchenOrderReadyMessage toNotification = assertInstanceOf(KitchenOrderReadyMessage.class, notification.received().getFirst());
		assertEquals(toOrder, toNotification, "the very same event: the receivers de-duplicate by its id");
		assertEquals(ready.eventId(), toNotification.eventId());
		assertEquals("READY", orderStatus(ready), "a delivery problem never undoes ready");
	}

	@Test
	void aSinkOnlyEverReceivesTheDeliveriesOfItsOwnDestination() {
		Ready ready = readyOrder();
		FakeOrderEventSink order = new FakeOrderEventSink();
		FakeNotificationSink notification = new FakeNotificationSink();

		assertEquals(Optional.empty(), delivery.attempt(ready.notificationDelivery(), order),
				"an Order sink cannot deliver a Notification delivery");
		assertEquals(Optional.empty(), delivery.attempt(ready.orderDelivery(), notification), "and the other way round");
		assertTrue(order.received().isEmpty() && notification.received().isEmpty());
		assertEquals(0, row(ready.orderDelivery()).get("attempt_count"));
		assertEquals(0, row(ready.notificationDelivery()).get("attempt_count"));

		// The database is shared with other tests: give up on every other pending delivery so only this order's two are due.
		jdbc.update("UPDATE outbox_deliveries SET status = 'FAILED', next_attempt_at = NULL WHERE status = 'PENDING' AND id NOT IN (?, ?)",
				ready.orderDelivery(), ready.notificationDelivery());
		makeDue(ready.orderDelivery());
		makeDue(ready.notificationDelivery());
		assertEquals(1, delivery.deliverDue(order, 10), "only the Order delivery was due for the Order sink");
		assertEquals("DELIVERED", row(ready.orderDelivery()).get("status"));
		assertEquals("PENDING", row(ready.notificationDelivery()).get("status"));
		assertEquals(1, delivery.deliverDue(notification, 10));
		assertEquals("DELIVERED", row(ready.notificationDelivery()).get("status"));
	}

	@Test
	void anUnknownOutcomeIsSentAgainWithTheSameEventIdSoTheReceiverMustDeDuplicate() {
		Ready ready = readyOrder();
		FakeNotificationSink notification = new FakeNotificationSink().mode(Mode.TIMEOUT);

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(ready.notificationDelivery(), notification));
		makeDue(ready.notificationDelivery());
		notification.mode(Mode.DELIVER);
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(ready.notificationDelivery(), notification));

		assertEquals(2, notification.received().size());
		assertEquals(notification.received().get(0), notification.received().get(1), "at-least-once: the same event id twice");
		String leaked = String.valueOf(row(ready.notificationDelivery()).get("last_error_code"));
		assertTrue(!leaked.contains("secret") && !leaked.contains("token"), leaked);
	}

	@Test
	void givingUpOnNotificationIsVisibleAndNeverTouchesTheOrderDeliveryOrTheOrder() {
		Ready ready = readyOrder();
		FakeNotificationSink notification = new FakeNotificationSink().mode(Mode.UNAVAILABLE);

		for (int attempt = 1; attempt <= OutboxDelivery.MAX_ATTEMPTS; attempt++) {
			makeDue(ready.notificationDelivery());
			delivery.attempt(ready.notificationDelivery(), notification);
		}

		Map<String, Object> failed = row(ready.notificationDelivery());
		assertEquals("FAILED", failed.get("status"));
		assertEquals(OutboxDelivery.MAX_ATTEMPTS, failed.get("attempt_count"));
		assertEquals(OutboxDelivery.MAX_ATTEMPTS_EXCEEDED, failed.get("last_error_code"));
		assertEquals("PENDING", row(ready.orderDelivery()).get("status"), "the Order delivery is its own story");
		assertEquals("READY", orderStatus(ready));
		// A delivery that was given up on is final: it is not tried again.
		makeDue(ready.notificationDelivery());
		assertEquals(Optional.empty(), delivery.attempt(ready.notificationDelivery(), new FakeNotificationSink()));
	}

	@Test
	void aDeliveryProblemAtNotificationNeverBlocksOrUndoesAnOrderDecision() {
		Ready stuck = readyOrder();
		delivery.attempt(stuck.notificationDelivery(), new FakeNotificationSink().mode(Mode.TIMEOUT));

		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID requestId = cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.OTHER, null)).request().getId();
		assertEquals(DecisionOutcome.CANCELLED, decisions.apply(SOURCE_ORDER, approved(restaurant, orderId, requestId, 2).message()).outcome());

		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
		assertEquals("PENDING", row(stuck.notificationDelivery()).get("status"), "the earlier problem is still just pending");
	}

	@Test
	void aCancellationIsNeverAnnouncedByKitchenAndARequestGoesToTheOrderServiceOnly() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID requestId = cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.OTHER, null)).request().getId();

		assertEquals(List.of("ORDER"),
				jdbc.queryForList("SELECT destination FROM outbox_deliveries WHERE restaurant_id = ?", String.class, restaurant));
		int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant);

		decisions.apply(SOURCE_ORDER, approved(restaurant, orderId, requestId, 2).message());

		assertEquals(eventsBefore, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant),
				"the Order service announces its own cancellation; Kitchen emits no OrderCancelled");
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ? AND destination = 'NOTIFICATION'",
				Integer.class, restaurant));
	}

	@Test
	void inProductionNothingCanDeliverAnythingBecauseNoRealSinkIsWired() {
		assertTrue(context.getBeansOfType(EventSink.class).isEmpty(), "no sink of any kind is a bean");
		assertTrue(context.getBeansOfType(OrderEventSink.class).isEmpty());
		assertTrue(context.getBeansOfType(NotificationEventSink.class).isEmpty());
		assertEquals(1, context.getBeansOfType(OutboxDeliveryService.class).size(),
				"the service exists but is only driven by whoever holds a sink");
	}
}
