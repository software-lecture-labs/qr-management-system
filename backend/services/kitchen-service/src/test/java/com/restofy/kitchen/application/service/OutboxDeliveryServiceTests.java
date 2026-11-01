package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery and failure behaviour of the outbox towards a (fake) Order sink on a real PostgreSQL. This proves the
 * port contract and the retry/failure rules; it is NOT a delivery to the real Order service, which does not exist yet.
 */
@SpringBootTest
class OutboxDeliveryServiceTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private OutboxDeliveryRepository deliveries;
	@Autowired
	private TransactionTemplate tx;
	@Autowired
	private JdbcTemplate jdbc;

	/** A READY order and the id of its (pending) delivery. */
	private record Ready(UUID restaurant, UUID orderId, UUID deliveryId) {
	}

	private Ready readyOrder() {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);
		UUID deliveryId = jdbc.queryForObject("SELECT id FROM outbox_deliveries WHERE restaurant_id = ? AND destination = 'ORDER'",
				UUID.class, restaurant);
		return new Ready(restaurant, orderId, deliveryId);
	}

	private void makeDue(UUID deliveryId) {
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ? AND status = 'PENDING'", deliveryId);
	}

	private Map<String, Object> delivery(UUID deliveryId) {
		return jdbc.queryForMap("SELECT status, attempt_count, next_attempt_at, delivered_at, last_error_code FROM outbox_deliveries "
				+ "WHERE id = ?", deliveryId);
	}

	/** Everything about the order itself that a delivery problem must never touch. */
	private Map<String, Object> orderState(Ready ready) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e WHERE e.restaurant_id = o.restaurant_id) AS events
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", ready.restaurant(), ready.orderId());
	}

	@Test
	void anAcknowledgedEventIsDeliveredOnceWithTheStoredStableMessage() {
		Ready ready = readyOrder();
		List<OutboundMessage> received = new ArrayList<>();

		Optional<DeliveryStatus> first = delivery.attempt(ready.deliveryId(), message -> {
			received.add(message);
			return OrderEventSink.Result.delivered();
		});

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), first);
		Map<String, Object> row = delivery(ready.deliveryId());
		assertEquals("DELIVERED", row.get("status"));
		assertEquals(1, row.get("attempt_count"));
		assertNotNull(row.get("delivered_at"));
		assertEquals(1, received.size());
		KitchenOrderReadyMessage message = (KitchenOrderReadyMessage) received.getFirst();
		assertEquals("KitchenOrderReady", message.eventType());
		assertEquals(1, message.schemaVersion());
		assertEquals(ready.orderId(), message.orderId());
		assertEquals(ready.restaurant(), message.restaurantId());
		assertEquals("READY", message.payload().preparationStatus());

		// A duplicate attempt is a no-op: the sink is not called again and nothing changes.
		AtomicInteger calls = new AtomicInteger();
		assertEquals(Optional.empty(), delivery.attempt(ready.deliveryId(), m -> {
			calls.incrementAndGet();
			return OrderEventSink.Result.delivered();
		}));
		assertEquals(0, calls.get());
		assertEquals(row, delivery(ready.deliveryId()));
	}

	@Test
	void whenTheOrderServiceIsUnreachableTheKitchenStaysReadyAndTheDeliveryIsVisiblyPendingForRetry() {
		Ready ready = readyOrder();
		Map<String, Object> orderBefore = orderState(ready);

		// A timeout or connection failure surfaces as an exception from the adapter: an UNKNOWN outcome.
		Optional<DeliveryStatus> result = delivery.attempt(ready.deliveryId(), message -> {
			throw new IllegalStateException("simulated timeout http://order.internal/secret-path?token=abc");
		});

		assertEquals(Optional.of(DeliveryStatus.PENDING), result);
		Map<String, Object> row = delivery(ready.deliveryId());
		assertEquals("PENDING", row.get("status"));
		assertEquals(1, row.get("attempt_count"));
		assertTrue(((Timestamp) row.get("next_attempt_at")).toInstant().isAfter(Instant.now().plusSeconds(2)), "retry is scheduled later");
		assertEquals("SINK_EXCEPTION", row.get("last_error_code"), "only a short safe code is stored, never the message, address or token");
		assertNull(row.get("delivered_at"));
		// K2-A15: the kitchen's truth is unaffected: still READY, no rollback, no second event, no extra history.
		assertEquals(orderBefore, orderState(ready));
		assertEquals("READY", orderState(ready).get("status"));
	}

	@Test
	void anUnknownOrMissingResultIsNeverTreatedAsSuccess() {
		Ready ready = readyOrder();

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(ready.deliveryId(), message -> null));
		assertEquals("NO_RESULT", delivery(ready.deliveryId()).get("last_error_code"));

		makeDue(ready.deliveryId());
		assertEquals(Optional.of(DeliveryStatus.PENDING),
				delivery.attempt(ready.deliveryId(), message -> OrderEventSink.Result.retry("HTTP_503 secret")));
		assertEquals("HTTP_503_secret", delivery(ready.deliveryId()).get("last_error_code"), "codes are sanitised");
		assertEquals(2, delivery(ready.deliveryId()).get("attempt_count"));
	}

	@Test
	void retriesSendTheSameEventIdAndStopAfterTheLimitAsAVisibleFailure() {
		Ready ready = readyOrder();
		List<UUID> eventIds = new ArrayList<>();
		OrderEventSink down = message -> {
			eventIds.add(message.eventId());
			return OrderEventSink.Result.retry("UNAVAILABLE");
		};

		for (int i = 1; i <= 12; i++) {
			makeDue(ready.deliveryId());
			Optional<DeliveryStatus> status = delivery.attempt(ready.deliveryId(), down);
			if (i <= 8) {
				assertTrue(status.isPresent(), "attempt " + i);
			} else {
				assertEquals(Optional.empty(), status, "no attempts after the limit");
			}
		}

		Map<String, Object> row = delivery(ready.deliveryId());
		assertEquals("FAILED", row.get("status"));
		assertEquals(8, row.get("attempt_count"));
		assertEquals("MAX_ATTEMPTS_EXCEEDED", row.get("last_error_code"));
		assertNull(row.get("next_attempt_at"));
		assertEquals(8, eventIds.size());
		assertEquals(1, eventIds.stream().distinct().count(), "every retry carries the same event id");
		assertEquals("READY", orderState(ready).get("status"), "even a FAILED delivery leaves the kitchen state alone");
	}

	@Test
	void aPermanentRejectionFailsAtOnceWithoutFurtherAttempts() {
		Ready ready = readyOrder();
		AtomicInteger calls = new AtomicInteger();

		assertEquals(Optional.of(DeliveryStatus.FAILED), delivery.attempt(ready.deliveryId(), message -> {
			calls.incrementAndGet();
			return OrderEventSink.Result.permanentFailure("UNSUPPORTED_SCHEMA");
		}));
		makeDue(ready.deliveryId());
		assertEquals(Optional.empty(), delivery.attempt(ready.deliveryId(), message -> {
			calls.incrementAndGet();
			return OrderEventSink.Result.delivered();
		}));

		assertEquals(1, calls.get());
		assertEquals("FAILED", delivery(ready.deliveryId()).get("status"));
	}

	@Test
	void aDeliveryThatIsNotDueYetIsNotAttempted() {
		Ready ready = readyOrder();
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?", ready.deliveryId());
		AtomicInteger calls = new AtomicInteger();

		assertEquals(Optional.empty(), delivery.attempt(ready.deliveryId(), message -> {
			calls.incrementAndGet();
			return OrderEventSink.Result.delivered();
		}));

		assertEquals(0, calls.get());
		assertEquals(0, delivery(ready.deliveryId()).get("attempt_count"));
	}

	@Test
	void theSinkIsCalledWithoutAnyDatabaseLockAndAParallelWorkerCannotSendTheSameEventAtTheSameTime() throws Exception {
		Ready ready = readyOrder();
		CountDownLatch insideSink = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		TransactionTemplate other = new TransactionTemplate(tx.getTransactionManager());
		other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		CompletableFuture<Optional<DeliveryStatus>> slow = CompletableFuture.supplyAsync(() -> delivery.attempt(ready.deliveryId(), message -> {
			calls.incrementAndGet();
			insideSink.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
			return OrderEventSink.Result.delivered();
		}));
		assertTrue(insideSink.await(20, TimeUnit.SECONDS));

		long started = System.nanoTime();
		// 1) No row lock is held while the (slow) external call runs: another transaction can lock the row at once.
		assertTrue(other.execute(status -> deliveries.findForUpdate(ready.deliveryId())).isPresent());
		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000, "the row was not locked by the in-flight call");
		// 2) A parallel worker does not send the same event while it is leased.
		assertEquals(Optional.empty(), delivery.attempt(ready.deliveryId(), message -> {
			calls.incrementAndGet();
			return OrderEventSink.Result.delivered();
		}));
		release.countDown();

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), slow.get(20, TimeUnit.SECONDS));
		assertEquals(1, calls.get(), "exactly one send for this event");
	}

	@Test
	void deliverDueHandlesOnlyDueDeliveriesOldestFirstAndHonoursTheLimit() {
		Ready first = readyOrder();
		Ready second = readyOrder();
		Ready notDue = readyOrder();
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?", notDue.deliveryId());
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '10 minutes' WHERE id = ?", first.deliveryId());
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '5 minutes' WHERE id = ?", second.deliveryId());
		jdbc.update("UPDATE outbox_deliveries SET status = 'FAILED', next_attempt_at = NULL WHERE status = 'PENDING' AND id NOT IN (?, ?, ?)",
				first.deliveryId(), second.deliveryId(), notDue.deliveryId());
		List<UUID> order = new ArrayList<>();
		OrderEventSink sink = message -> {
			order.add(((KitchenOrderReadyMessage) message).orderId());
			return OrderEventSink.Result.delivered();
		};

		assertEquals(1, delivery.deliverDue(sink, 1), "the limit is honoured");
		assertEquals(List.of(first.orderId()), order, "oldest due first");
		assertEquals(1, delivery.deliverDue(sink, 10), "only the remaining due one; the future one is untouched");
		assertEquals(List.of(first.orderId(), second.orderId()), order);
		assertEquals("PENDING", delivery(notDue.deliveryId()).get("status"));
		assertFalse(order.contains(notDue.orderId()));
	}
}
