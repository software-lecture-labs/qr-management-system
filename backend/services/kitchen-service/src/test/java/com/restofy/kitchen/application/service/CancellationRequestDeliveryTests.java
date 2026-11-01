package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.support.FakeOrderEventSink;
import com.restofy.kitchen.support.FakeOrderEventSink.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How the outbox hands a cancellation REQUEST to the (fake) Order service. A positive answer from the fake means "the
 * request arrived", never "the order is cancelled": the request and the kitchen order must look the same afterwards.
 * This proves Kitchen's behaviour towards the proposed port contract, not a delivery to a real Order service.
 */
@SpringBootTest
class CancellationRequestDeliveryTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private JdbcTemplate jdbc;

	private record Pending(UUID restaurant, UUID orderId, UUID requestId, UUID deliveryId) {
	}

	private Pending pendingRequest() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID productId = jdbc.queryForObject("""
				SELECT i.product_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? LIMIT 1
				""", UUID.class, restaurant, orderId);
		var requested = cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.OUT_OF_STOCK, "iç not", productId, null));
		UUID deliveryId = jdbc.queryForObject("""
				SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND e.event_type = 'KitchenCancellationRequested'
				""", UUID.class, restaurant);
		return new Pending(restaurant, orderId, requested.request().getId(), deliveryId);
	}

	private Map<String, Object> delivery(UUID deliveryId) {
		return jdbc.queryForMap("SELECT status, attempt_count, delivered_at, last_error_code FROM outbox_deliveries WHERE id = ?", deliveryId);
	}

	/** The request and the kitchen order as they are: delivery must never change either. */
	private Map<String, Object> state(Pending pending) {
		return jdbc.queryForMap("""
				SELECT r.status AS request_status, r.decided_at, o.status AS order_status, o.cancelled_at, o.version
				FROM cancellation_requests r JOIN kitchen_orders o ON o.restaurant_id = r.restaurant_id AND o.order_id = r.order_id
				WHERE r.id = ?
				""", pending.requestId());
	}

	@Test
	void anAcknowledgedRequestIsDeliveredOnceInItsStableMinimalFormAndNothingIsDecided() {
		Pending pending = pendingRequest();
		Map<String, Object> before = state(pending);
		FakeOrderEventSink order = new FakeOrderEventSink();

		Optional<DeliveryStatus> result = delivery.attempt(pending.deliveryId(), order);

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), result);
		assertEquals(1, order.received().size());
		OutboundMessage sent = order.received().getFirst();
		KitchenCancellationRequestedMessage message = assertInstanceOf(KitchenCancellationRequestedMessage.class, sent);
		assertEquals("KitchenCancellationRequested", message.eventType());
		assertEquals(1, message.schemaVersion());
		assertEquals(pending.restaurant(), message.restaurantId());
		assertEquals(pending.orderId(), message.orderId());
		assertEquals(pending.requestId(), message.requestId());
		assertEquals("OUT_OF_STOCK", message.payload().reasonCode());
		assertEquals(pending.orderId(), message.payload().orderId());
		assertEquals(before, state(pending), "the order did not become cancelled and the request was not decided by the acknowledgement");
		assertEquals("REQUESTED", state(pending).get("request_status"));
		assertNull(state(pending).get("cancelled_at"));

		// A second attempt is a no-op: the sink is not called again.
		assertEquals(Optional.empty(), delivery.attempt(pending.deliveryId(), order));
		assertEquals(1, order.received().size());
	}

	@Test
	void whenTheOrderServiceIsUnreachableTheRequestStaysAndTheDeliveryStaysPendingForRetry() {
		Pending pending = pendingRequest();
		Map<String, Object> before = state(pending);

		for (Mode failing : new Mode[] { Mode.UNAVAILABLE, Mode.TIMEOUT }) {
			FakeOrderEventSink order = new FakeOrderEventSink().mode(failing);
			// The retry waits for its back-off; here the wait is skipped so the next attempt is due.
			jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ? AND status = 'PENDING'",
					pending.deliveryId());
			assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(pending.deliveryId(), order));
		}

		Map<String, Object> row = delivery(pending.deliveryId());
		assertEquals("PENDING", row.get("status"));
		assertEquals(2, row.get("attempt_count"));
		assertNull(row.get("delivered_at"));
		assertEquals(before, state(pending), "a delivery problem neither cancels the order nor drops the request");
		String leaked = String.valueOf(row.get("last_error_code"));
		assertEquals(false, leaked.contains("secret") || leaked.contains("token"), leaked);
	}

	@Test
	void aDefiniteRejectionOfTheEventMarksTheDeliveryFailedButNeverDecidesTheRequest() {
		Pending pending = pendingRequest();
		Map<String, Object> before = state(pending);

		assertEquals(Optional.of(DeliveryStatus.FAILED), delivery.attempt(pending.deliveryId(), new FakeOrderEventSink().mode(Mode.PERMANENT)));

		assertEquals("FAILED", delivery(pending.deliveryId()).get("status"));
		assertEquals(before, state(pending), "the request is still REQUESTED: only an Order decision can change it");
	}
}
