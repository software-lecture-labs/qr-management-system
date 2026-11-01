package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-04 / 4.1: POST /api/v1/kitchen/orders/{orderId}/cancellation-requests against a real PostgreSQL. A request is
 * only a record plus an outbox event: the order is never cancelled by it and keeps its preparation status.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenCancellationRequestApiTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;

	// ---- helpers -------------------------------------------------------------------------------------------------

	private UUID order(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		return orderId;
	}

	private UUID preparingOrder(UUID restaurant, UUID actor) {
		UUID orderId = order(restaurant);
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		return orderId;
	}

	private UUID readyOrder(UUID restaurant, UUID actor) {
		UUID orderId = preparingOrder(restaurant, actor);
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);
		return orderId;
	}

	/** The product of the order's line, a product the kitchen really received (needed by an OUT_OF_STOCK request). */
	private UUID productOf(UUID restaurant, UUID orderId) {
		return jdbc.queryForObject("""
				SELECT i.product_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? LIMIT 1
				""", UUID.class, restaurant, orderId);
	}

	/** A body for any reason; a stock (OUT_OF_STOCK) reason carries the order's own product. */
	private String bodyFor(UUID restaurant, UUID orderId, String reason, String text) {
		String json = body(reason, text);
		return "OUT_OF_STOCK".equals(reason) ? json.replace("}", ",\"productId\":\"" + productOf(restaurant, orderId) + "\"}") : json;
	}

	private static String path(UUID orderId) {
		return "/api/v1/kitchen/orders/" + orderId + "/cancellation-requests";
	}

	private static String body(String reason, String text) {
		return text == null ? "{\"reasonCode\":\"" + reason + "\"}" : "{\"reasonCode\":\"" + reason + "\",\"reasonText\":\"" + text + "\"}";
	}

	private static MockHttpServletRequestBuilder ask(UUID orderId, UUID key, String json) {
		return post(path(orderId)).header(KEY, key.toString()).contentType("application/json").content(json);
	}

	private ResultActions send(UUID restaurant, UUID actor, UUID orderId, UUID key, String json) throws Exception {
		return mvc.perform(ask(orderId, key, json).with(kitchen(restaurant, actor)).with(csrf()));
	}

	private int requests(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	private int events(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = 'KitchenCancellationRequested'",
				Integer.class, restaurant);
	}

	/** Everything about the kitchen order that a request must never touch. */
	private Map<String, Object> orderState(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.cancelled_at, o.cancellation_reason, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e
				        WHERE e.restaurant_id = o.restaurant_id AND e.event_type = 'KitchenOrderReady') AS ready_events
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	private List<Integer> fire(int threads, IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int n = i;
			futures.add(pool.submit(() -> {
				start.await();
				return mvc.perform(request.apply(n)).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> future : futures) {
			statuses.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		Collections.sort(statuses);
		return statuses;
	}

	// ---- creating a request --------------------------------------------------------------------------------------

	@Test
	void aWaitingOrderGetsAnAcceptedRequestAndStaysExactlyAsItWas() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID key = UUID.randomUUID();
		Map<String, Object> before = orderState(restaurant, orderId);

		String response = send(restaurant, actor, orderId, key, body("CUSTOMER_REQUEST", "Müşteri vazgeçti")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.orderId").value(orderId.toString()))
				.andExpect(jsonPath("$.reasonCode").value("CUSTOMER_REQUEST")).andExpect(jsonPath("$.reasonText").value("Müşteri vazgeçti"))
				.andExpect(jsonPath("$.requestedByUserId").value(actor.toString())).andExpect(jsonPath("$.requestedAt").isNotEmpty())
				.andExpect(jsonPath("$.decidedAt").value((Object) null)).andExpect(jsonPath("$.decisionReasonCode").value((Object) null))
				.andReturn().getResponse().getContentAsString();

		assertFalse(response.contains("cancelledAt") || response.contains("\"CANCELLED\""),
				"a request is never shown as a cancellation: " + response);
		assertEquals(before, orderState(restaurant, orderId), "the order, its history and its events are untouched");
		Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM cancellation_requests WHERE restaurant_id = ?", restaurant);
		assertEquals("REQUESTED", stored.get("status"));
		assertEquals(key, stored.get("command_id"));
		assertEquals(actor, stored.get("requested_by_user_id"));
		assertNull(stored.get("decided_at"));
	}

	@Test
	void aPreparingOrderIsAcceptedToo() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = preparingOrder(restaurant, actor);
		Map<String, Object> before = orderState(restaurant, orderId);

		send(restaurant, actor, orderId, UUID.randomUUID(), body("KITCHEN_ERROR", null)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.reasonText").value((Object) null));

		assertEquals(before, orderState(restaurant, orderId));
		assertEquals("PREPARING", orderState(restaurant, orderId).get("status"));
	}

	@Test
	void everyReasonIsAcceptedAndBlankTextIsStoredAsNone() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String reason : List.of("OUT_OF_STOCK", "CUSTOMER_REQUEST", "KITCHEN_ERROR", "OTHER")) {
			UUID orderId = order(restaurant);
			send(restaurant, UUID.randomUUID(), orderId, UUID.randomUUID(), bodyFor(restaurant, orderId, reason, "   "))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.reasonCode").value(reason)).andExpect(jsonPath("$.reasonText").value((Object) null));
		}
		assertEquals(4, requests(restaurant));
		UUID orderId = order(restaurant);
		send(restaurant, UUID.randomUUID(), orderId, UUID.randomUUID(), body("OTHER", "ş".repeat(500))).andExpect(status().isAccepted());
	}

	@Test
	void theRequestIsStoredTogetherWithExactlyOneMinimalPendingEventForTheOrderService() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID key = UUID.randomUUID();

		String requestId = JsonPath.read(send(restaurant, UUID.randomUUID(), orderId, key,
				bodyFor(restaurant, orderId, "OUT_OF_STOCK", "Tavuk bitti, bu not olaya girmez")).andExpect(status().isAccepted())
						.andReturn().getResponse().getContentAsString(),
				"$.requestId");

		Map<String, Object> event = jdbc.queryForMap("SELECT * FROM outbox_events WHERE restaurant_id = ? AND event_type = "
				+ "'KitchenCancellationRequested'", restaurant);
		assertEquals("CANCELLATION_REQUEST", event.get("aggregate_type"));
		assertEquals(UUID.fromString(requestId), event.get("aggregate_id"));
		assertEquals(key, event.get("causation_id"));
		assertEquals(1, event.get("schema_version"));
		String payload = event.get("payload").toString();
		assertTrue(payload.contains(orderId.toString()) && payload.contains("OUT_OF_STOCK") && payload.contains(productOf(restaurant,
				orderId).toString()), payload);
		assertFalse(payload.contains("Tavuk") || payload.contains("not olaya"), "the free text never leaves Kitchen: " + payload);
		Map<String, Object> delivery = jdbc.queryForMap("SELECT destination, status, attempt_count FROM outbox_deliveries WHERE "
				+ "restaurant_id = ? AND event_id = ?",
				restaurant, event.get("id"));
		assertEquals("ORDER", delivery.get("destination"));
		assertEquals("PENDING", delivery.get("status"));
		assertEquals(0, delivery.get("attempt_count"));
		assertEquals(1, events(restaurant));
	}

	// ---- order state ---------------------------------------------------------------------------------------------

	@Test
	void aReadyOrderCannotBeAskedToCancelBecauseTheDecisionIsStillOpen() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = readyOrder(restaurant, actor);
		Map<String, Object> before = orderState(restaurant, orderId);

		send(restaurant, actor, orderId, UUID.randomUUID(), body("OTHER", null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.currentStatus").value("READY"))
				.andExpect(jsonPath("$.retryable").value(false));

		assertEquals(0, requests(restaurant));
		assertEquals(0, events(restaurant));
		assertEquals(before, orderState(restaurant, orderId), "READY stays READY");
	}

	@Test
	void anAlreadyCancelledOrderCannotBeAskedAgain() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, UUID.randomUUID(), "CANCELLED", Instant.parse("2026-10-20T10:00:00Z"));

		send(restaurant, UUID.randomUUID(), orderId, UUID.randomUUID(), body("OTHER", null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
		assertEquals(0, requests(restaurant));
	}

	// ---- validation ----------------------------------------------------------------------------------------------

	@Test
	void anInvalidBodyIsABadRequestAndNothingIsStored() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		for (String json : List.of("{}", "{\"reasonText\":\"x\"}", "{\"reasonCode\":null}", "{\"reasonCode\":\"\"}", "{\"reasonCode\":\"   \"}",
				body("BROKEN", null), body("out_of_stock", null), body("OTHER", "x".repeat(501)), body("OTHER", "bell\\u0007"),
				body("OTHER", "line\\nbreak"), "{\"reasonCode\":", "[]", "\"OTHER\"", "", "not json")) {
			send(restaurant, UUID.randomUUID(), orderId, UUID.randomUUID(), json).andExpect(status().isBadRequest());
		}
		mvc.perform(post(path(orderId)).header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/kitchen/orders/not-a-uuid/cancellation-requests").header(KEY, UUID.randomUUID().toString())
				.contentType("application/json").content(body("OTHER", null)).with(kitchen(restaurant)).with(csrf()))
						.andExpect(status().isBadRequest());
		assertEquals(0, requests(restaurant));
		assertEquals(0, events(restaurant));
	}

	@Test
	void theIdempotencyKeyIsMandatoryUniqueAndACanonicalUuid() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		String json = body("OTHER", null);

		mvc.perform(post(path(orderId)).contentType("application/json").content(json).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(post(path(orderId)).contentType("application/json").content(json).header(KEY, UUID.randomUUID().toString())
				.header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		for (String bad : List.of("not-a-uuid", "{}", "null", "x".repeat(5_000), " 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22x",
				"0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22,0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a23")) {
			mvc.perform(post(path(orderId)).contentType("application/json").content(json).header(KEY, bad).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().isBadRequest());
		}
		assertEquals(0, requests(restaurant));
	}

	// ---- repeats and conflicts -----------------------------------------------------------------------------------

	@Test
	void theSameRequestAgainIsAnsweredFromTheStoredOneWithoutASecondRequestOrEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID key = UUID.randomUUID();
		String first = send(restaurant, actor, orderId, key, body("KITCHEN_ERROR", "Yanlış sipariş")).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		// The uppercase spelling of the key is the same command.
		String again = mvc.perform(post(path(orderId)).header(KEY, key.toString().toUpperCase()).contentType("application/json")
				.content(body("KITCHEN_ERROR", "Yanlış sipariş")).with(kitchen(restaurant, actor)).with(csrf())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertEquals(first, again, "the very same stored request");
		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));
	}

	@Test
	void theSameKeyForAnotherRequestIsAConflict() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID otherOrder = order(restaurant);
		UUID key = UUID.randomUUID();
		send(restaurant, actor, orderId, key, body("KITCHEN_ERROR", "Yanlış sipariş")).andExpect(status().isAccepted());

		send(restaurant, actor, orderId, key, body("OTHER", "Yanlış sipariş")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT")).andExpect(jsonPath("$.retryable").value(false));
		send(restaurant, actor, orderId, key, body("KITCHEN_ERROR", "Başka not")).andExpect(status().isConflict());
		send(restaurant, actor, orderId, key, body("KITCHEN_ERROR", null)).andExpect(status().isConflict());
		send(restaurant, UUID.randomUUID(), orderId, key, body("KITCHEN_ERROR", "Yanlış sipariş")).andExpect(status().isConflict());
		send(restaurant, actor, otherOrder, key, body("KITCHEN_ERROR", "Yanlış sipariş")).andExpect(status().isConflict());

		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));
	}

	@Test
	void aSecondRequestWithAnotherKeyWhileOneIsUndecidedIsRefusedButARejectedOneAllowsANewRequest() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		send(restaurant, actor, orderId, UUID.randomUUID(), body("OTHER", null)).andExpect(status().isAccepted());

		send(restaurant, actor, orderId, UUID.randomUUID(), body("KITCHEN_ERROR", null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CANCELLATION_ALREADY_REQUESTED")).andExpect(jsonPath("$.retryable").value(false));
		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));

		// The Order service rejected it (written by hand here: the decision flow is a later task): a new request may follow.
		jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now() WHERE restaurant_id = ?", restaurant);
		send(restaurant, actor, orderId, UUID.randomUUID(), body("KITCHEN_ERROR", null)).andExpect(status().isAccepted());
		assertEquals(2, requests(restaurant));
		assertEquals(2, events(restaurant), "each request has its own event");
	}

	@Test
	void aRepeatStillAnswers200EvenIfTheOrderMovedOnButANewRequestDoesNot() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = preparingOrder(restaurant, actor);
		UUID key = UUID.randomUUID();
		send(restaurant, actor, orderId, key, body("OTHER", null)).andExpect(status().isAccepted());
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);

		send(restaurant, actor, orderId, key, body("OTHER", null)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"));
		send(restaurant, actor, orderId, UUID.randomUUID(), body("OTHER", null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
		assertEquals("READY", orderState(restaurant, orderId).get("status"), "the request did not stop the order from becoming ready");
	}

	// ---- security and tenant -------------------------------------------------------------------------------------

	@Test
	void onlyAnAuthenticatedKitchenUserWithCsrfMayRequestAndOtherRestaurantsSeeNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = order(restaurant);
		String json = body("OTHER", null);

		mvc.perform(ask(orderId, UUID.randomUUID(), json).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(ask(orderId, UUID.randomUUID(), json).header("X-Role", "KITCHEN").header("X-Restaurant-Id", restaurant.toString())
				.with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(ask(orderId, UUID.randomUUID(), json).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(ask(orderId, UUID.randomUUID(), json).with(kitchen(restaurant))).andExpect(status().isForbidden());
		for (var request : List.of(put(path(orderId)), patch(path(orderId)), delete(path(orderId)))) {
			mvc.perform(request.header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().is4xxClientError());
		}
		// Another restaurant, or an order that does not exist, looks identical: not found.
		UUID intruder = UUID.randomUUID();
		send(intruder, UUID.randomUUID(), orderId, UUID.randomUUID(), json).andExpect(status().isNotFound());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), json).andExpect(status().isNotFound());
		mvc.perform(ask(orderId, UUID.randomUUID(), json).header("X-Restaurant-Id", restaurant.toString()).with(kitchen(intruder)).with(csrf()))
				.andExpect(status().isNotFound());

		assertEquals(0, requests(restaurant));
		assertEquals(0, requests(intruder));
	}

	@Test
	void restaurantUserAndTimeAreNeverTakenFromTheClient() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		String forged = body("OTHER", null).replace("}", ",\"restaurantId\":\"" + UUID.randomUUID() + "\",\"requestedByUserId\":\""
				+ UUID.randomUUID() + "\",\"requestedAt\":\"1999-01-01T00:00:00Z\",\"status\":\"APPROVED\",\"decidedAt\":\"1999-01-01T00:00:00Z\"}");

		mvc.perform(ask(orderId, UUID.randomUUID(), forged).with(kitchen(restaurant, actor)).with(csrf())).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.requestedByUserId").value(actor.toString()))
				.andExpect(jsonPath("$.decidedAt").value((Object) null));
		Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM cancellation_requests WHERE restaurant_id = ?", restaurant);
		assertEquals("REQUESTED", stored.get("status"));
		assertNull(stored.get("decided_at"));
		assertTrue(((Timestamp) stored.get("requested_at")).toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
	}

	// ---- races ---------------------------------------------------------------------------------------------------

	@Test
	void parallelRequestsWithDifferentKeysCreateExactlyOneRequestAndOneEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);

		List<Integer> statuses = fire(8, n -> ask(orderId, UUID.randomUUID(), body("OTHER", null)).with(kitchen(restaurant, actor)).with(csrf()));

		assertEquals(1, statuses.stream().filter(s -> s == 202).count(), "exactly one request was created: " + statuses);
		assertEquals(7, statuses.stream().filter(s -> s == 409).count(), "the others were refused: " + statuses);
		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));
	}

	@Test
	void parallelRequestsWithTheSameKeyCreateExactlyOneRequestAndTheOthersAreRepeats() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = order(restaurant);
		UUID key = UUID.randomUUID();

		List<Integer> statuses = fire(8, n -> ask(orderId, key, body("OTHER", null)).with(kitchen(restaurant, actor)).with(csrf()));

		assertEquals(1, statuses.stream().filter(s -> s == 202).count(), "exactly one created it: " + statuses);
		assertEquals(7, statuses.stream().filter(s -> s == 200).count(), "the others were verified repeats: " + statuses);
		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));
	}

	@Test
	void aRequestRacingWithMarkReadyEndsInAConsistentOrderEitherWay() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = preparingOrder(restaurant, actor);

		List<Integer> statuses = fire(2, n -> n == 0
				? ask(orderId, UUID.randomUUID(), body("OTHER", null)).with(kitchen(restaurant, actor)).with(csrf())
				: patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").header(KEY, UUID.randomUUID().toString())
						.with(kitchen(restaurant, actor)).with(csrf()));

		// Whichever came first: mark-ready always succeeds (a request does not block it); the request is accepted only if it won.
		Map<String, Object> state = orderState(restaurant, orderId);
		assertEquals("READY", state.get("status"));
		assertEquals(1L, ((Number) state.get("ready_events")).longValue(), "exactly one ready event");
		assertTrue(statuses.contains(200), "mark-ready succeeded: " + statuses);
		assertEquals(statuses.contains(202) ? 1 : 0, requests(restaurant));
		assertEquals(requests(restaurant), events(restaurant));
	}
}
