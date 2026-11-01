package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** WAITING -> PREPARING over HTTP and on a real PostgreSQL: result, persistence, history, command identity. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenStartPreparationApiTests {

	private static final String LIST = "/api/v1/kitchen/orders";
	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;

	private static String startPath(UUID orderId) {
		return LIST + "/" + orderId + "/start-preparation";
	}

	/** A start command as a KITCHEN user of the restaurant sends it (CSRF token present). */
	private static MockHttpServletRequestBuilder start(UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return patch(startPath(orderId)).with(kitchen(restaurant, actor)).with(csrf()).header(KEY, key.toString());
	}

	private UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "12", "A-" + orderId.toString().substring(0, 6), 1,
				UUID.randomUUID(), item("Burger", 2, "az pişmiş")));
		return orderId;
	}

	/** Everything a rejected or repeated command must leave untouched. */
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
	void waitingOrderIsStartedWithTheServerTimeTheActorAndTheCommandIdRecorded() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		Instant before = Instant.now();

		mvc.perform(start(orderId, restaurant, actor, key))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").value(orderId.toString()))
				.andExpect(jsonPath("$.status").value("PREPARING"))
				.andExpect(jsonPath("$.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.readyAt").doesNotExist())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].note").value("az pişmiş"));

		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("PREPARING", row.get("status"));
		Instant startedAt = ((Timestamp) row.get("started_at")).toInstant();
		assertFalse(startedAt.isBefore(before.minusSeconds(1)), "server time expected");
		assertNull(row.get("ready_at"));
		assertEquals(1L, row.get("version"));
		assertEquals(2L, row.get("history"));

		Map<String, Object> entry = jdbc.queryForMap("""
				SELECT from_status, to_status, actor_user_id, command_id, occurred_at FROM kitchen_status_history h
				JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'PREPARING'
				""", restaurant, orderId);
		assertEquals("WAITING", entry.get("from_status"));
		assertEquals(actor, entry.get("actor_user_id"));
		assertEquals(key, entry.get("command_id"));
		assertEquals(startedAt, ((Timestamp) entry.get("occurred_at")).toInstant());
	}

	@Test
	void startedOrderLeavesTheWaitingListAndAppearsAsPreparingKeepingFifoOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID older = accept(restaurant);
		UUID newer = accept(restaurant);
		Instant received = Instant.now();
		jdbc.update("UPDATE kitchen_orders SET received_at = ? WHERE restaurant_id = ? AND order_id = ?",
				Timestamp.from(received.minusSeconds(2)), restaurant, older);
		jdbc.update("UPDATE kitchen_orders SET received_at = ? WHERE restaurant_id = ? AND order_id = ?",
				Timestamp.from(received.minusSeconds(1)), restaurant, newer);

		mvc.perform(start(newer, restaurant, UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isOk());

		mvc.perform(get(LIST + "?status=WAITING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(older.toString()))
				.andExpect(jsonPath("$.content[0].newArrival").value(true));
		mvc.perform(get(LIST + "?status=PREPARING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(newer.toString()))
				.andExpect(jsonPath("$.content[0].newArrival").value(false));
		mvc.perform(get(LIST + "?status=ACTIVE").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].orderId").value(older.toString()))
				.andExpect(jsonPath("$.content[1].orderId").value(newer.toString()));
		mvc.perform(get(LIST + "/" + newer).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.status").value("PREPARING"))
				.andExpect(jsonPath("$.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.items[0].productName").value("Burger"));
	}

	@Test
	void repeatingTheSameCommandIsA200WithoutAnyChange() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, actor, key)).andExpect(status().isOk());
		Map<String, Object> afterFirst = state(restaurant, orderId);

		for (int i = 0; i < 3; i++) {
			mvc.perform(start(orderId, restaurant, actor, key))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("PREPARING"));
		}

		assertEquals(afterFirst, state(restaurant, orderId));
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void anotherCommandOnAnAlreadyPreparingOrderIsAConflictEvenFromTheSameActor() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		Map<String, Object> afterFirst = state(restaurant, orderId);

		UUID otherKey = UUID.randomUUID();
		mvc.perform(start(orderId, restaurant, actor, otherKey))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.currentStatus").value("PREPARING"));
		// A different operator reaching the same order with a fresh command is equally not a repeat.
		mvc.perform(start(orderId, restaurant, UUID.randomUUID(), UUID.randomUUID()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.currentStatus").value("PREPARING"));

		assertEquals(afterFirst, state(restaurant, orderId));
		assertEquals(0, historyWithKey(restaurant, otherKey));
	}

	@Test
	void theSameCommandIdFromAnotherActorIsNotTreatedAsARepeat() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, UUID.randomUUID(), key)).andExpect(status().isOk());
		Map<String, Object> afterFirst = state(restaurant, orderId);

		mvc.perform(start(orderId, restaurant, UUID.randomUUID(), key))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertEquals(afterFirst, state(restaurant, orderId));
	}

	@Test
	void aCommandIdCannotBeReusedForAnotherOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID first = accept(restaurant);
		UUID second = accept(restaurant);
		mvc.perform(start(first, restaurant, actor, key)).andExpect(status().isOk());
		Map<String, Object> secondBefore = state(restaurant, second);

		mvc.perform(start(second, restaurant, actor, key)).andExpect(status().isConflict());

		assertEquals(secondBefore, state(restaurant, second));
		assertEquals("WAITING", state(restaurant, second).get("status"));
	}

	@Test
	void aRepeatAfterTheOrderHasMovedOnReportsTheRealStateInsteadOfAStaleSuccess() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(start(orderId, restaurant, actor, key)).andExpect(status().isOk());
		// Preparation was finished by someone else in the meantime (READY command arrives in task 2.2).
		jdbc.update("UPDATE kitchen_orders SET status = 'READY', ready_at = now(), version = version + 1 "
				+ "WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
		Map<String, Object> before = state(restaurant, orderId);

		mvc.perform(start(orderId, restaurant, actor, key))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.currentStatus").value("READY"));

		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void theSameCommandIdMayBeUsedInDifferentRestaurants() throws Exception {
		UUID key = UUID.randomUUID();
		UUID restaurantA = UUID.randomUUID();
		UUID restaurantB = UUID.randomUUID();
		UUID orderA = accept(restaurantA);
		UUID orderB = accept(restaurantB);

		mvc.perform(start(orderA, restaurantA, UUID.randomUUID(), key)).andExpect(status().isOk());
		mvc.perform(start(orderB, restaurantB, UUID.randomUUID(), key)).andExpect(status().isOk());

		assertEquals("PREPARING", state(restaurantA, orderA).get("status"));
		assertEquals("PREPARING", state(restaurantB, orderB).get("status"));
	}

	@Test
	void readyAndCancelledOrdersAreConflictsWithoutAnySideEffect() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Instant t = Instant.parse("2026-10-17T10:00:00Z");
		for (String status : new String[] { "READY", "CANCELLED" }) {
			UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, status, t);
			UUID key = UUID.randomUUID();
			Map<String, Object> before = state(restaurant, orderId);

			mvc.perform(start(orderId, restaurant, UUID.randomUUID(), key))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.status").value(409))
					.andExpect(jsonPath("$.currentStatus").value(status))
					.andExpect(jsonPath("$.trace").doesNotExist());

			assertEquals(before, state(restaurant, orderId), status + " must be untouched");
			assertEquals(0, historyWithKey(restaurant, key));
		}
	}

	@Test
	void missingOrMalformedCommandIdIsABadRequestAndNothingChanges() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		Map<String, Object> before = state(restaurant, orderId);

		mvc.perform(patch(startPath(orderId)).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
		for (String bad : new String[] { "not-a-uuid", "", " ", "12345", UUID.randomUUID() + "x" }) {
			mvc.perform(patch(startPath(orderId)).with(kitchen(restaurant)).with(csrf()).header(KEY, bad))
					.andExpect(status().isBadRequest());
		}

		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void unauthenticatedAndWrongRoleCallersCannotStartAnOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		Map<String, Object> before = state(restaurant, orderId);
		String key = UUID.randomUUID().toString();

		mvc.perform(patch(startPath(orderId)).with(csrf()).header(KEY, key)).andExpect(status().isUnauthorized());
		mvc.perform(patch(startPath(orderId)).header(KEY, key)).andExpect(status().isForbidden()); // no CSRF, no identity
		// ADMIN may read but never change preparation state.
		mvc.perform(patch(startPath(orderId)).with(admin(restaurant)).with(csrf()).header(KEY, key)).andExpect(status().isForbidden());
		mvc.perform(patch(startPath(orderId)).with(user("waiter").roles("WAITER")).with(csrf()).header(KEY, key))
				.andExpect(status().isForbidden());
		mvc.perform(patch(startPath(orderId)).with(kitchen(restaurant)).header(KEY, key)).andExpect(status().isForbidden()); // CSRF stays on

		assertEquals(before, state(restaurant, orderId));
		mvc.perform(get(LIST + "/" + orderId).with(admin(restaurant))).andExpect(status().isOk()); // ADMIN still reads
	}

	@Test
	void anotherRestaurantCannotStartTheOrderAndForgedTenantHeadersDoNothing() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID orderId = accept(owner);
		Map<String, Object> before = state(owner, orderId);
		UUID key = UUID.randomUUID();

		mvc.perform(start(orderId, intruder, UUID.randomUUID(), key)
						.header("X-Restaurant-Id", owner.toString()).header("X-Role", "ADMIN")
						.header("X-User-Id", UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());
		mvc.perform(patch(startPath(orderId) + "?restaurantId=" + owner).with(kitchen(intruder)).with(csrf())
						.header(KEY, UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());

		assertEquals(before, state(owner, orderId));
		assertEquals(0, historyWithKey(owner, key));
		assertEquals(0, historyWithKey(intruder, key));
	}

	@Test
	void requestBodyCannotInfluenceTheTransition() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		String forged = """
				{"status":"READY","startedAt":"2000-01-01T00:00:00Z","readyAt":"2000-01-01T00:00:01Z",
				 "restaurantId":"%s","actorId":"%s","actorUserId":"%s","commandId":"%s","price":1}
				""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(start(orderId, restaurant, actor, UUID.randomUUID()).contentType("application/json").content(forged))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PREPARING"))
				.andExpect(jsonPath("$.readyAt").doesNotExist());

		Map<String, Object> row = state(restaurant, orderId);
		assertTrue(((Timestamp) row.get("started_at")).toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
		assertNull(row.get("ready_at"));
		assertEquals(actor, jdbc.queryForObject("""
				SELECT h.actor_user_id FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'PREPARING'
				""", UUID.class, restaurant, orderId));
	}

	@Test
	void malformedAndUnknownOrderIdsAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();

		mvc.perform(patch(LIST + "/not-a-uuid/start-preparation").with(kitchen(restaurant)).with(csrf())
						.header(KEY, UUID.randomUUID().toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
		mvc.perform(start(UUID.randomUUID(), restaurant, UUID.randomUUID(), UUID.randomUUID()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void parallelRepeatsOfTheSameCommandApplyExactlyOneTransition() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);

		List<Integer> codes = runParallel(8, i -> start(orderId, restaurant, actor, key));

		assertTrue(codes.stream().allMatch(code -> code == 200), "one applies, the rest are verified repeats: " + codes);
		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("PREPARING", row.get("status"));
		assertNotNull(row.get("started_at"));
		assertEquals(1L, row.get("version"), "exactly one update");
		assertEquals(2L, row.get("history"), "initial WAITING entry plus exactly one transition");
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void parallelDifferentCommandsLetExactlyOneWinAndRejectTheRestWithoutSideEffects() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);

		List<Integer> codes = runParallel(8, i -> start(orderId, restaurant, UUID.randomUUID(), UUID.randomUUID()));

		assertEquals(1, codes.stream().filter(code -> code == 200).count(), codes.toString());
		assertEquals(7, codes.stream().filter(code -> code == 409).count(), codes.toString());
		Map<String, Object> row = state(restaurant, orderId);
		assertEquals(1L, row.get("version"));
		assertEquals(2L, row.get("history"));
	}

	private List<Integer> runParallel(int threads, IntFunction<MockHttpServletRequestBuilder> request)
			throws Exception {
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

	@Test
	void ordersOnTheSameTableAreStartedIndependently() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		ingest.ingest(message(restaurant, first, session, "5", "A-1", 1, UUID.randomUUID(), item("Çorba", 1, null)));
		ingest.ingest(message(restaurant, second, session, "5", "A-2", 1, UUID.randomUUID(), item("Pilav", 1, null)));

		mvc.perform(start(first, restaurant, UUID.randomUUID(), UUID.randomUUID())).andExpect(status().isOk());

		assertEquals("PREPARING", state(restaurant, first).get("status"));
		assertEquals("WAITING", state(restaurant, second).get("status"));
		assertEquals(1L, state(restaurant, second).get("history"));
	}

	@Test
	void serviceUsedDirectlyAlsoCannotCrossTenants() {
		UUID owner = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(owner, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		UUID intruder = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		assertThrows(KitchenOrderNotFoundException.class,
				() -> preparation.startPreparation(intruder, actor, command, orderId));
		assertEquals("WAITING", state(owner, orderId).get("status"));
	}
}
