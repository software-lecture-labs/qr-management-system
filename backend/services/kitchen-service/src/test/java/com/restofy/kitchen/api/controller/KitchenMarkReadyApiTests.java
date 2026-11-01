package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.domain.enums.OptionAction;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.option;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PREPARING -> READY over HTTP and on a real PostgreSQL: result, persistence, history, views, no side effects. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenMarkReadyApiTests {

	private static final String LIST = "/api/v1/kitchen/orders";
	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private static MockHttpServletRequestBuilder command(String action, UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return patch(LIST + "/" + orderId + "/" + action).with(kitchen(restaurant, actor)).with(csrf()).header(KEY, key.toString());
	}

	private static MockHttpServletRequestBuilder start(UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return command("start-preparation", orderId, restaurant, actor, key);
	}

	private static MockHttpServletRequestBuilder ready(UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return command("mark-ready", orderId, restaurant, actor, key);
	}

	private UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "12", "A-" + orderId.toString().substring(0, 6), 1,
				UUID.randomUUID(), item("Burger", 2, "az pişmiş", option(OptionAction.REMOVE, "Soğan", 1, null),
						option(OptionAction.ADD, "Peynir", 2, "çift")), item("Ayran", 1, null)));
		return orderId;
	}

	/** A PREPARING order started by {@code actor}. */
	private UUID prepared(UUID restaurant, UUID actor) throws Exception {
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		return orderId;
	}

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	private int historyWithKey(UUID restaurant, UUID key) {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_status_history WHERE restaurant_id = ? AND command_id = ?",
				Integer.class, restaurant, key);
	}

	@Test
	void fullFlowWaitingToPreparingToReadyKeepsStartedAtAndRecordsEveryStep() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID readyKey = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		Timestamp startedAt = (Timestamp) state(restaurant, orderId).get("started_at");

		mvc.perform(ready(orderId, restaurant, actor, readyKey))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").value(orderId.toString()))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.readyAt").isNotEmpty());

		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("READY", row.get("status"));
		assertEquals(startedAt, row.get("started_at"), "startedAt must not change");
		Timestamp readyAt = (Timestamp) row.get("ready_at");
		assertFalse(readyAt.before(startedAt), "readyAt >= startedAt");
		assertEquals(2L, row.get("version"));
		assertEquals(3L, row.get("history"), "WAITING at receipt, PREPARING, READY");

		Map<String, Object> entry = jdbc.queryForMap("""
				SELECT from_status, to_status, actor_user_id, command_id, occurred_at FROM kitchen_status_history h
				JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'READY'
				""", restaurant, orderId);
		assertEquals("PREPARING", entry.get("from_status"));
		assertEquals(actor, entry.get("actor_user_id"));
		assertEquals(readyKey, entry.get("command_id"));
		assertEquals(readyAt, entry.get("occurred_at"));
	}

	@Test
	void aWaitingOrderCannotBeMarkedReadyAndNothingChanges() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		UUID key = UUID.randomUUID();
		Map<String, Object> before = state(restaurant, orderId);

		mvc.perform(ready(orderId, restaurant, UUID.randomUUID(), key))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.currentStatus").value("WAITING"))
				.andExpect(jsonPath("$.trace").doesNotExist());

		assertEquals(before, state(restaurant, orderId));
		assertEquals(0, historyWithKey(restaurant, key));
	}

	@Test
	void repeatingTheSameReadyCommandIsA200WithoutAnyChange() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(ready(orderId, restaurant, actor, key)).andExpect(status().isOk());
		Map<String, Object> afterFirst = state(restaurant, orderId);

		for (int i = 0; i < 3; i++) {
			mvc.perform(ready(orderId, restaurant, actor, key))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("READY"));
		}

		assertEquals(afterFirst, state(restaurant, orderId));
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void anotherCommandOrAnotherOperatorOnAReadyOrderIsAConflict() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(ready(orderId, restaurant, actor, key)).andExpect(status().isOk());
		Map<String, Object> afterFirst = state(restaurant, orderId);
		UUID otherKey = UUID.randomUUID();

		mvc.perform(ready(orderId, restaurant, actor, otherKey))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("READY"));
		mvc.perform(ready(orderId, restaurant, UUID.randomUUID(), key)).andExpect(status().isConflict());
		mvc.perform(ready(orderId, restaurant, UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isConflict());

		assertEquals(afterFirst, state(restaurant, orderId));
		assertEquals(0, historyWithKey(restaurant, otherKey));
	}

	@Test
	void aCommandIdBelongsToOneTransitionOnly() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID startKey = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, actor, startKey)).andExpect(status().isOk());
		Map<String, Object> before = state(restaurant, orderId);

		// The start command's id cannot finish the order.
		mvc.perform(ready(orderId, restaurant, actor, startKey)).andExpect(status().isConflict());
		assertEquals(before, state(restaurant, orderId));
		assertEquals("PREPARING", state(restaurant, orderId).get("status"));

		// A ready command's id cannot be reused to start another order.
		UUID readyKey = UUID.randomUUID();
		mvc.perform(ready(orderId, restaurant, actor, readyKey)).andExpect(status().isOk());
		UUID second = accept(restaurant);
		Map<String, Object> secondBefore = state(restaurant, second);
		mvc.perform(start(second, restaurant, actor, readyKey)).andExpect(status().isConflict());
		assertEquals(secondBefore, state(restaurant, second));
	}

	@Test
	void readyIsTerminalStartingAReadyOrderIsAConflictAndTheCancelledAreUntouched() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		mvc.perform(ready(orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		Map<String, Object> ready = state(restaurant, orderId);

		mvc.perform(start(orderId, restaurant, actor, UUID.randomUUID()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("READY"));
		assertEquals(ready, state(restaurant, orderId));

		UUID cancelled = new KitchenOrderSeeder(jdbc).seed(restaurant, "CANCELLED", Instant.parse("2026-10-17T10:00:00Z"));
		Map<String, Object> cancelledBefore = state(restaurant, cancelled);
		mvc.perform(ready(cancelled, restaurant, actor, UUID.randomUUID()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
		assertEquals(cancelledBefore, state(restaurant, cancelled));
	}

	@Test
	void unauthenticatedWrongRoleAdminAndMissingCsrfCannotMarkReady() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = prepared(restaurant, UUID.randomUUID());
		Map<String, Object> before = state(restaurant, orderId);
		String path = LIST + "/" + orderId + "/mark-ready";
		String key = UUID.randomUUID().toString();

		mvc.perform(patch(path).with(csrf()).header(KEY, key)).andExpect(status().isUnauthorized());
		mvc.perform(patch(path).header(KEY, key)).andExpect(status().isForbidden());
		mvc.perform(patch(path).with(admin(restaurant)).with(csrf()).header(KEY, key)).andExpect(status().isForbidden());
		mvc.perform(patch(path).with(user("waiter").roles("WAITER")).with(csrf()).header(KEY, key)).andExpect(status().isForbidden());
		mvc.perform(patch(path).with(kitchen(restaurant)).header(KEY, key)).andExpect(status().isForbidden());

		assertEquals(before, state(restaurant, orderId));
		mvc.perform(get(LIST + "/" + orderId).with(admin(restaurant))).andExpect(status().isOk()); // ADMIN still reads
	}

	@Test
	void anotherRestaurantCannotMarkReadyAndForgedTenantInputDoesNothing() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID orderId = prepared(owner, UUID.randomUUID());
		Map<String, Object> before = state(owner, orderId);
		UUID key = UUID.randomUUID();

		mvc.perform(ready(orderId, intruder, UUID.randomUUID(), key)
						.header("X-Restaurant-Id", owner.toString()).header("X-Role", "ADMIN"))
				.andExpect(status().isNotFound());
		mvc.perform(patch(LIST + "/" + orderId + "/mark-ready?restaurantId=" + owner).with(kitchen(intruder)).with(csrf())
						.header(KEY, UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());

		assertEquals(before, state(owner, orderId));
		assertEquals(0, historyWithKey(owner, key));
		assertEquals(0, historyWithKey(intruder, key));
	}

	@Test
	void requestBodyCannotInfluenceTheReadyTransition() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);
		Timestamp startedAt = (Timestamp) state(restaurant, orderId).get("started_at");
		String forged = """
				{"status":"WAITING","startedAt":"2000-01-01T00:00:00Z","readyAt":"2000-01-01T00:00:01Z",
				 "restaurantId":"%s","actorId":"%s","commandId":"%s"}
				""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(ready(orderId, restaurant, actor, UUID.randomUUID()).contentType("application/json").content(forged))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));

		Map<String, Object> row = state(restaurant, orderId);
		assertEquals(startedAt, row.get("started_at"));
		assertTrue(((Timestamp) row.get("ready_at")).toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
	}

	@Test
	void missingMalformedAndUnknownInputsAreRejectedWithoutChanges() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = prepared(restaurant, UUID.randomUUID());
		Map<String, Object> before = state(restaurant, orderId);
		String path = LIST + "/" + orderId + "/mark-ready";

		mvc.perform(patch(path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		for (String bad : new String[] { "not-a-uuid", "", " ", "12345" }) {
			mvc.perform(patch(path).with(kitchen(restaurant)).with(csrf()).header(KEY, bad)).andExpect(status().isBadRequest());
		}
		mvc.perform(patch(LIST + "/not-a-uuid/mark-ready").with(kitchen(restaurant)).with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isBadRequest());
		mvc.perform(ready(UUID.randomUUID(), restaurant, UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isNotFound());

		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void readyOrderLeavesTheActiveListsAndShowsInTheReadyListInFifoOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		Instant received = Instant.now();
		UUID first = prepared(restaurant, actor);
		UUID second = prepared(restaurant, actor);
		UUID stillPreparing = prepared(restaurant, actor);
		jdbc.update("UPDATE kitchen_orders SET received_at = ? WHERE restaurant_id = ? AND order_id = ?",
				Timestamp.from(received.minusSeconds(3)), restaurant, first);
		jdbc.update("UPDATE kitchen_orders SET received_at = ? WHERE restaurant_id = ? AND order_id = ?",
				Timestamp.from(received.minusSeconds(2)), restaurant, second);
		// Finish the newer one first: the READY list must still be oldest-received first.
		mvc.perform(ready(second, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(ready(first, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		mvc.perform(get(LIST + "?status=READY").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].orderId").value(first.toString()))
				.andExpect(jsonPath("$.content[0].status").value("READY"))
				.andExpect(jsonPath("$.content[0].newArrival").value(false))
				.andExpect(jsonPath("$.content[0].itemCount").value(2))
				.andExpect(jsonPath("$.content[1].orderId").value(second.toString()));
		mvc.perform(get(LIST + "?status=PREPARING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(stillPreparing.toString()));
		mvc.perform(get(LIST + "?status=ACTIVE").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(stillPreparing.toString()));
		mvc.perform(get(LIST + "?status=WAITING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void readyDetailKeepsTheOrderSnapshotAndShowsBothTimesInOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		String waitingBody = mvc.perform(get(LIST + "/" + orderId).with(kitchen(restaurant))).andReturn().getResponse().getContentAsString();
		mvc.perform(start(orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		String readyResponse = mvc.perform(ready(orderId, restaurant, actor, UUID.randomUUID()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String detail = mvc.perform(get(LIST + "/" + orderId).with(admin(restaurant)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		// Items, notes and ADD/REMOVE options are exactly what they were when the order arrived.
		assertEquals(JsonPath.<Object>read(waitingBody, "$.items"), JsonPath.<Object>read(detail, "$.items"));
		assertEquals(JsonPath.<Object>read(detail, "$.items"), JsonPath.<Object>read(readyResponse, "$.items"));
		assertEquals("12", JsonPath.<String>read(detail, "$.tableNumber"));
		assertEquals("READY", JsonPath.<String>read(detail, "$.status"));
		Instant started = Instant.parse(JsonPath.<String>read(detail, "$.startedAt"));
		Instant readyAt = Instant.parse(JsonPath.<String>read(detail, "$.readyAt"));
		Instant received = Instant.parse(JsonPath.<String>read(detail, "$.receivedAt"));
		assertFalse(started.isBefore(received));
		assertFalse(readyAt.isBefore(started));
		// Before the order is finished the readyAt is an explicit null.
		assertNull(JsonPath.<Object>read(waitingBody, "$.readyAt"));
		assertTrue(waitingBody.contains("\"readyAt\":null") && waitingBody.contains("\"startedAt\":null"));
	}

	@Test
	void ordersOnTheSameTableAreFinishedIndependently() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		ingest.ingest(message(restaurant, first, session, "5", "A-1", 1, UUID.randomUUID(), item("Çorba", 1, null)));
		ingest.ingest(message(restaurant, second, session, "5", "A-2", 1, UUID.randomUUID(), item("Pilav", 1, null)));
		mvc.perform(start(first, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(ready(first, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		assertEquals("READY", state(restaurant, first).get("status"));
		assertEquals("WAITING", state(restaurant, second).get("status"));
		assertEquals(1L, state(restaurant, second).get("history"));
	}

	@Test
	void parallelRepeatsOfTheSameReadyCommandApplyExactlyOneTransition() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);

		List<Integer> codes = runParallel(8, i -> ready(orderId, restaurant, actor, key));

		assertTrue(codes.stream().allMatch(code -> code == 200), codes.toString());
		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("READY", row.get("status"));
		assertEquals(2L, row.get("version"), "start plus exactly one ready update");
		assertEquals(3L, row.get("history"));
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void parallelDifferentReadyCommandsLetExactlyOneWin() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = prepared(restaurant, actor);

		List<Integer> codes = runParallel(8, i -> ready(orderId, restaurant, UUID.randomUUID(), UUID.randomUUID()));

		assertEquals(1, codes.stream().filter(code -> code == 200).count(), codes.toString());
		assertEquals(7, codes.stream().filter(code -> code == 409).count(), codes.toString());
		Map<String, Object> row = state(restaurant, orderId);
		assertEquals(2L, row.get("version"));
		assertEquals(3L, row.get("history"));
		assertNotEquals(null, row.get("ready_at"));
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
