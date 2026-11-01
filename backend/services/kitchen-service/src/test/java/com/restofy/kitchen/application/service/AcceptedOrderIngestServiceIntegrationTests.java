package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.OptionAction;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.option;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static com.restofy.kitchen.support.OrderMessages.withVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AcceptedOrderIngestServiceIntegrationTests {

	@Autowired
	private AcceptedOrderIngestService service;
	@Autowired
	private KitchenOrderRepository orders;
	@Autowired
	private KitchenStatusHistoryRepository history;
	@Autowired
	private TransactionTemplate tx;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private io.micrometer.core.instrument.MeterRegistry meters;

	private int countOrders(UUID restaurantId) {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE restaurant_id = ?", Integer.class, restaurantId);
	}

	@Test
	void acceptedOrderIsStoredAsWaitingWithItemsOptionsAndInitialHistory() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID event = UUID.randomUUID();
		OrderAcceptedMessage msg = message(restaurant, orderId, UUID.randomUUID(), " 12 ", " A-100 ", 3, event,
				item("Burger", 2, "az pişmiş", option(OptionAction.REMOVE, "Soğan", 1, null),
						option(OptionAction.ADD, "Peynir", 2, "çift")),
				item("Ayran", 1, null));

		assertEquals(IngestOutcome.CREATED, service.ingest(msg));

		tx.executeWithoutResult(s -> {
			KitchenOrder stored = orders.findByRestaurantIdAndOrderId(restaurant, orderId).orElseThrow();
			assertEquals(KitchenOrderStatus.WAITING, stored.getStatus());
			assertEquals("12", stored.getTableNumberSnapshot());
			assertEquals("A-100", stored.getOrderNumberSnapshot());
			assertEquals(msg.tableSessionId(), stored.getTableSessionId());
			assertEquals(3L, stored.getSourceOrderVersion());
			assertEquals(event, stored.getSourceEventId());
			assertTrue(stored.getStartedAt() == null && stored.getReadyAt() == null);
			assertEquals(2, stored.getItems().size());
			assertEquals("Burger", stored.getItems().get(0).getProductNameSnapshot());
			assertEquals("az pişmiş", stored.getItems().get(0).getNoteSnapshot());
			assertTrue(stored.getItems().get(0).getOptionsSnapshot().contains("Soğan"));
			assertEquals(0, stored.getItems().get(0).getCancelledQuantity());

			var entries = history.findByRestaurantIdAndKitchenOrderIdOrderByOccurredAtAsc(restaurant, stored.getId());
			assertEquals(1, entries.size());
			assertEquals(KitchenOrderStatus.WAITING, entries.get(0).getToStatus());
			assertEquals(null, entries.get(0).getFromStatus());
			assertEquals(event, entries.get(0).getSourceEventId());
		});
	}

	@Test
	void sameEventOrSameVersionRedeliveryDoesNotCreateASecondRecord() {
		UUID restaurant = UUID.randomUUID();
		OrderAcceptedMessage msg = simple(restaurant, UUID.randomUUID());
		assertEquals(IngestOutcome.CREATED, service.ingest(msg));

		assertEquals(IngestOutcome.DUPLICATE, service.ingest(msg));
		assertEquals(IngestOutcome.DUPLICATE, service.ingest(withVersion(msg, 1, UUID.randomUUID())));
		assertEquals(1, countOrders(restaurant));
	}

	@Test
	void olderVersionNeverOverwritesAndNewerVersionIsNotAppliedSilently() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		OrderAcceptedMessage v5 = withVersion(simple(restaurant, orderId), 5, UUID.randomUUID());
		assertEquals(IngestOutcome.CREATED, service.ingest(v5));

		OrderAcceptedMessage older = message(restaurant, orderId, UUID.randomUUID(), "99", "ZZ-9", 3, UUID.randomUUID(),
				item("Değişmiş", 9, "yeni not"));
		assertEquals(IngestOutcome.STALE_IGNORED, service.ingest(older));
		OrderAcceptedMessage newer = message(restaurant, orderId, UUID.randomUUID(), "99", "ZZ-9", 8, UUID.randomUUID(),
				item("Değişmiş", 9, "yeni not"));
		assertEquals(IngestOutcome.NEWER_VERSION_NOT_APPLIED, service.ingest(newer));

		tx.executeWithoutResult(s -> {
			KitchenOrder stored = orders.findByRestaurantIdAndOrderId(restaurant, orderId).orElseThrow();
			assertEquals(5L, stored.getSourceOrderVersion());
			assertEquals("T1", stored.getTableNumberSnapshot());
			assertEquals("Burger", stored.getItems().get(0).getProductNameSnapshot());
			assertEquals(1, stored.getItems().size());
		});
	}

	@Test
	void everyOutcomeIncludingUnappliedAndRejectedMessagesIsCountedForTraceability() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		OrderAcceptedMessage v5 = withVersion(simple(restaurant, orderId), 5, UUID.randomUUID());
		double created = counter("CREATED");
		double duplicate = counter("DUPLICATE");
		double stale = counter("STALE_IGNORED");
		double newer = counter("NEWER_VERSION_NOT_APPLIED");
		double rejected = counter("REJECTED_INVALID");

		service.ingest(v5);
		service.ingest(v5);
		service.ingest(withVersion(v5, 3, UUID.randomUUID()));
		service.ingest(withVersion(v5, 9, UUID.randomUUID()));
		service.ingest(withVersion(v5, 10, UUID.randomUUID()));
		assertThrows(InvalidOrderMessageException.class, () -> service.ingest(null));

		assertEquals(1, counter("CREATED") - created);
		assertEquals(1, counter("DUPLICATE") - duplicate);
		assertEquals(1, counter("STALE_IGNORED") - stale);
		assertEquals(2, counter("NEWER_VERSION_NOT_APPLIED") - newer);
		assertEquals(1, counter("REJECTED_INVALID") - rejected);
		// The unapplied newer versions changed nothing.
		assertEquals(5L, orders.findByRestaurantIdAndOrderId(restaurant, orderId).orElseThrow().getSourceOrderVersion());
	}

	private double counter(String outcome) {
		var counter = meters.find("kitchen.order.ingest").tag("outcome", outcome).counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void concurrentDeliveriesOfTheSameOrderCreateExactlyOneRecord() throws Exception {
		UUID restaurant = UUID.randomUUID();
		OrderAcceptedMessage msg = simple(restaurant, UUID.randomUUID());
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<IngestOutcome>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				Callable<IngestOutcome> task = () -> {
					start.await();
					return service.ingest(msg);
				};
				results.add(pool.submit(task));
			}
			start.countDown();
			int created = 0;
			int duplicates = 0;
			for (Future<IngestOutcome> f : results) {
				IngestOutcome outcome = f.get();
				if (outcome == IngestOutcome.CREATED) {
					created++;
				} else if (outcome == IngestOutcome.DUPLICATE) {
					duplicates++;
				}
			}
			assertEquals(1, created);
			assertEquals(threads - 1, duplicates);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, countOrders(restaurant));
		assertEquals(1, jdbc.queryForObject("""
				SELECT count(*) FROM kitchen_status_history WHERE restaurant_id = ?
				""", Integer.class, restaurant));
	}

	@Test
	void ordersOnTheSameTableSessionStaySeparateAndTenantsDoNotCollide() {
		UUID restaurant = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID sharedOrderId = UUID.randomUUID();
		assertEquals(IngestOutcome.CREATED, service.ingest(
				message(restaurant, sharedOrderId, session, "5", "A-1", 1, UUID.randomUUID(), item("Çorba", 1, null))));
		assertEquals(IngestOutcome.CREATED, service.ingest(
				message(restaurant, UUID.randomUUID(), session, "5", "A-2", 1, UUID.randomUUID(), item("Pilav", 1, null))));
		UUID otherRestaurant = UUID.randomUUID();
		assertEquals(IngestOutcome.CREATED, service.ingest(
				message(otherRestaurant, sharedOrderId, session, "5", "B-1", 1, UUID.randomUUID(), item("Kebap", 1, null))));

		assertEquals(2, countOrders(restaurant));
		assertEquals(1, countOrders(otherRestaurant));
		assertEquals("A-1", orders.findByRestaurantIdAndOrderId(restaurant, sharedOrderId).orElseThrow().getOrderNumberSnapshot());
		assertEquals("B-1", orders.findByRestaurantIdAndOrderId(otherRestaurant, sharedOrderId).orElseThrow().getOrderNumberSnapshot());
	}

	@Test
	void invalidMessagesAreRejectedAndNothingIsStored() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		OrderAcceptedMessage ok = simple(restaurant, orderId);

		OrderAcceptedMessage blankNumber = message(restaurant, orderId, UUID.randomUUID(), "T1", "  ", 1, UUID.randomUUID(),
				item("Burger", 1, null));
		OrderAcceptedMessage zeroQuantity = message(restaurant, orderId, UUID.randomUUID(), "T1", "A-1", 1, UUID.randomUUID(),
				item("Burger", 0, null));
		OrderAcceptedMessage noItems = message(restaurant, orderId, UUID.randomUUID(), "T1", "A-1", 1, UUID.randomUUID());
		OrderAcceptedMessage badOption = message(restaurant, orderId, UUID.randomUUID(), "T1", "A-1", 1, UUID.randomUUID(),
				item("Burger", 1, null, option(OptionAction.ADD, " ", 1, null)));
		OrderAcceptedMessage longNote = message(restaurant, orderId, UUID.randomUUID(), "T1", "A-1", 1, UUID.randomUUID(),
				item("Burger", 1, "x".repeat(1001)));
		OrderAcceptedMessage wrongSchema = new OrderAcceptedMessage(UUID.randomUUID(), 2, ok.occurredAt(), null, restaurant,
				orderId, 1L, "A-1", UUID.randomUUID(), "T1", ok.items());
		OrderAcceptedMessage.Item dup = item("Burger", 1, null);
		OrderAcceptedMessage duplicateItems = message(restaurant, orderId, UUID.randomUUID(), "T1", "A-1", 1,
				UUID.randomUUID(), dup, dup);

		for (OrderAcceptedMessage invalid : List.of(blankNumber, zeroQuantity, noItems, badOption, longNote, wrongSchema,
				duplicateItems)) {
			assertThrows(InvalidOrderMessageException.class, () -> service.ingest(invalid));
		}
		assertThrows(InvalidOrderMessageException.class, () -> service.ingest(null));
		assertEquals(0, countOrders(restaurant));
	}
}
