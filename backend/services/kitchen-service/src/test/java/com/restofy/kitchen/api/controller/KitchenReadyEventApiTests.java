package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenEventRecorder;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "ready" event in the outbox on a real PostgreSQL: exactly one per READY transition, in the same transaction,
 * never for anything else, never twice. (Local event persistence only: nothing is delivered to the Order service.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenReadyEventApiTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private OutboxEventRepository outbox;
	@Autowired
	private KitchenEventRecorder recorder;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "12", "A-1", 1, UUID.randomUUID(),
				item("Burger", 2, "gizli-not")));
		return orderId;
	}

	private static MockHttpServletRequestBuilder command(String action, UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return patch("/api/v1/kitchen/orders/" + orderId + "/" + action).with(kitchen(restaurant, actor)).with(csrf())
				.header(KEY, key.toString());
	}

	private UUID prepared(UUID restaurant, UUID actor) throws Exception {
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		return orderId;
	}

	private int events(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	private int deliveries(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	@Test
	void noEventIsRecordedUntilTheOrderIsReady() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		assertEquals(0, events(restaurant), "receiving an order records no event");

		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		assertEquals(0, events(restaurant), "starting preparation records no event (KitchenPreparationStarted is only a proposal)");

		mvc.perform(command("mark-ready", UUID.randomUUID(), restaurant, actor, UUID.randomUUID())).andExpect(status().isNotFound());
		assertEquals(0, events(restaurant));
	}

	@Test
	void readyRecordsExactlyOneEventAndOnePendingDeliveryWithTheAgreedContent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID readyKey = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);

		mvc.perform(command("mark-ready", orderId, restaurant, actor, readyKey)).andExpect(status().isOk());

		assertEquals(1, events(restaurant));
		assertEquals(2, deliveries(restaurant), "one event, one delivery for the Order and one for the Notification service");
		Map<String, Object> order = jdbc.queryForMap(
				"SELECT version, started_at, ready_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
		OutboxEvent event = outbox.findByRestaurantIdAndAggregateId(restaurant, orderId).getFirst();

		assertEquals("KitchenOrderReady", event.getEventType());
		assertEquals(1, event.getSchemaVersion());
		assertEquals("KITCHEN_ORDER", event.getAggregateType());
		assertEquals(orderId, event.getAggregateId());
		assertEquals(((Number) order.get("version")).longValue(), event.getAggregateVersion(), "version after the change");
		assertEquals(readyKey, event.getCausationId());
		assertEquals(((Timestamp) order.get("ready_at")).toInstant(), event.getOccurredAt());

		KitchenOrderReadyMessage message = (KitchenOrderReadyMessage) recorder.toMessage(event);
		assertEquals(event.getId(), message.eventId());
		assertEquals("READY", message.payload().preparationStatus());
		assertEquals(((Timestamp) order.get("started_at")).toInstant(), message.payload().startedAt());
		assertEquals(((Timestamp) order.get("ready_at")).toInstant(), message.payload().readyAt());

		Map<String, Object> delivery = jdbc.queryForMap(
				"SELECT destination, status, attempt_count, delivered_at, next_attempt_at FROM outbox_deliveries WHERE restaurant_id = ? AND "
						+ "destination = 'ORDER'", restaurant);
		assertEquals("ORDER", delivery.get("destination"));
		assertEquals("PENDING", delivery.get("status"));
		assertEquals(0, delivery.get("attempt_count"));
		assertNull(delivery.get("delivered_at"), "nothing was delivered: this is local persistence only");
		assertNotNull(delivery.get("next_attempt_at"));
	}

	@Test
	void theStoredEventCarriesNoPersonalDataTableProductNoteOrPrice() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		String stored = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE restaurant_id = ?", String.class, restaurant);
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE restaurant_id = ?", restaurant);
		String everything = stored + row.values();

		for (String leak : List.of("gizli-not", "Burger", actor.toString(), "price", "tableNumber", "productName")) {
			assertFalse(everything.contains(leak), "the event must not contain '" + leak + "'");
		}
		assertEquals(List.of("preparationStatus", "readyAt", "startedAt").stream().sorted().toList(),
				JsonPath.<Map<String, Object>>read(stored, "$").keySet().stream().sorted().toList());
	}

	@Test
	void repeatingTheReadyCommandNeverRecordsASecondEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(command("mark-ready", orderId, restaurant, actor, key)).andExpect(status().isOk());
		UUID firstEvent = outbox.findByRestaurantIdAndAggregateId(restaurant, orderId).getFirst().getId();

		for (int i = 0; i < 3; i++) {
			mvc.perform(command("mark-ready", orderId, restaurant, actor, key)).andExpect(status().isOk());
		}
		// A different command on a READY order is a conflict and must not add anything either.
		mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());

		assertEquals(1, events(restaurant));
		assertEquals(2, deliveries(restaurant), "one event, one delivery for the Order and one for the Notification service");
		assertEquals(firstEvent, outbox.findByRestaurantIdAndAggregateId(restaurant, orderId).getFirst().getId(), "the event id is stable");
	}

	@Test
	void rejectedReadyCommandsRecordNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID waiting = accept(restaurant);

		mvc.perform(command("mark-ready", waiting, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/mark-ready").with(kitchen(UUID.randomUUID())).with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isNotFound());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/mark-ready").with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());

		assertEquals(0, events(restaurant));
		assertEquals(0, deliveries(restaurant));
	}

	@Test
	void parallelReadyCommandsRecordExactlyOneEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);

		List<Integer> sameKey = runParallel(8,
				i -> command("mark-ready", orderId, restaurant, actor, UUID.fromString("0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22")));
		assertTrue(sameKey.stream().allMatch(code -> code == 200), sameKey.toString());
		assertEquals(1, events(restaurant));

		UUID other = prepared(restaurant, actor);
		List<Integer> differentKeys = runParallel(8, i -> command("mark-ready", other, restaurant, UUID.randomUUID(), UUID.randomUUID()));
		assertEquals(1, differentKeys.stream().filter(code -> code == 200).count(), differentKeys.toString());
		assertEquals(2, events(restaurant), "one event per ready order, never more");
		assertEquals(4, deliveries(restaurant), "two events, each with two deliveries");
	}

	@Test
	void ordersOnTheSameTableGetTheirOwnIndependentEvents() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		ingest.ingest(message(restaurant, first, session, "5", "A-1", 1, UUID.randomUUID(), item("Çorba", 1, null)));
		ingest.ingest(message(restaurant, second, session, "5", "A-2", 1, UUID.randomUUID(), item("Pilav", 1, null)));
		for (UUID orderId : List.of(first, second)) {
			mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
			mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		}

		assertEquals(2, events(restaurant));
		assertEquals(1, outbox.findByRestaurantIdAndAggregateId(restaurant, first).size());
		assertEquals(1, outbox.findByRestaurantIdAndAggregateId(restaurant, second).size());
		assertNotEquals(outbox.findByRestaurantIdAndAggregateId(restaurant, first).getFirst().getId(),
				outbox.findByRestaurantIdAndAggregateId(restaurant, second).getFirst().getId());
	}

	@Test
	void theDatabaseItselfAllowsOnlyOneReadyEventPerOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
				    schema_version, payload, occurred_at) VALUES (?, ?, 'KITCHEN_ORDER', ?, 9, 'KitchenOrderReady', 1, '{}'::jsonb, now())
				""", UUID.randomUUID(), restaurant, orderId));
		assertEquals(1, events(restaurant));
	}

	private List<Integer> runParallel(int threads, IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				int n = i;
				futures.add(pool.submit(() -> {
					go.await();
					return mvc.perform(request.apply(n)).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> codes = new ArrayList<>();
			for (Future<Integer> future : futures) {
				codes.add(future.get());
			}
			return codes;
		} finally {
			pool.shutdownNow();
		}
	}
}
