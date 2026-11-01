package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real races on PostgreSQL: another transaction holds the order row lock while a command arrives. Covers the lock
 * timeout (503, retryable, nothing changed), a command that waits and then finds the order changed under it (409)
 * and a command that waits and then finds its own command already done (verified repeat, 200).
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenConcurrencyApiTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenOrderRepository orders;
	@Autowired
	private TransactionTemplate tx;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		return orderId;
	}

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version, o.cancelled_at,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	private int historyWithKey(UUID restaurant, UUID key) {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_status_history WHERE restaurant_id = ? AND command_id = ?",
				Integer.class, restaurant, key);
	}

	/** Holds the order's row lock for {@code holdMillis}, optionally changing it, then commits. */
	private CompletableFuture<Void> holdRowLock(UUID restaurant, UUID orderId, long holdMillis, Runnable whileHolding,
			CountDownLatch locked) {
		return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
			orders.findForUpdate(restaurant, orderId).orElseThrow();
			locked.countDown();
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(holdMillis));
			if (whileHolding != null) {
				whileHolding.run();
			}
		}));
	}

	@Test
	void aLockHeldLongerThanTheTimeoutIs503BusyWithRetryAfterAndChangesNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		UUID key = UUID.randomUUID();
		Map<String, Object> before = state(restaurant, orderId);

		CountDownLatch locked = new CountDownLatch(1);
		CompletableFuture<Void> holder = holdRowLock(restaurant, orderId, 8_000, null, locked);
		locked.await();
		long started = System.nanoTime();

		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
						.header(KEY, key.toString()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "2"))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.code").value("ORDER_BUSY"))
				.andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.trace").doesNotExist());
		long waited = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
		holder.get(15, TimeUnit.SECONDS);

		assertTrue(waited >= 4 && waited <= 7, "the wait is bounded by the 5 s lock timeout, was " + waited + " s");
		assertEquals(before, state(restaurant, orderId));
		assertEquals(0, historyWithKey(restaurant, key));

		// The very same command can safely be sent again once the lock is gone, and then it is applied exactly once.
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
				.header(KEY, key.toString())).andExpect(status().isOk());
		assertEquals("PREPARING", state(restaurant, orderId).get("status"));
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void aCommandThatWaitsForTheLockAndThenFindsTheOrderCancelledIs409AndChangesNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		UUID key = UUID.randomUUID();

		CountDownLatch locked = new CountDownLatch(1);
		// An Order-confirmed cancellation (not implemented before phase 4) commits while the command is waiting.
		CompletableFuture<Void> canceller = holdRowLock(restaurant, orderId, 1_500, () -> jdbc.update(
				"UPDATE kitchen_orders SET status = 'CANCELLED', cancelled_at = now(), version = version + 1 "
						+ "WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId), locked);
		locked.await();

		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant)).with(csrf())
						.header(KEY, key.toString()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
				.andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
		canceller.get(10, TimeUnit.SECONDS);

		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("CANCELLED", row.get("status"));
		assertEquals(null, row.get("started_at"));
		assertEquals(null, row.get("ready_at"));
		assertEquals(1L, row.get("history"), "only the initial WAITING entry: the command left no trace");
		assertEquals(0, historyWithKey(restaurant, key));
	}

	@Test
	void aCommandWaitingBehindAnIdenticalOneBecomesAVerifiedRepeatAndIsNotAppliedTwice() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		UUID key = UUID.randomUUID();
		CountDownLatch locked = new CountDownLatch(1);
		CompletableFuture<Void> holder = holdRowLock(restaurant, orderId, 1_000, null, locked);
		locked.await();

		CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> perform(restaurant, actor, orderId, key));
		CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> perform(restaurant, actor, orderId, key));

		assertEquals(200, first.get(15, TimeUnit.SECONDS));
		assertEquals(200, second.get(15, TimeUnit.SECONDS));
		holder.get(5, TimeUnit.SECONDS);
		Map<String, Object> row = state(restaurant, orderId);
		assertEquals("PREPARING", row.get("status"));
		assertEquals(1L, row.get("version"));
		assertEquals(2L, row.get("history"));
		assertEquals(1, historyWithKey(restaurant, key));
	}

	@Test
	void aSeededCancelledOrderIsAlsoUntouchedByBothCommands() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, "CANCELLED", Instant.parse("2026-10-17T10:00:00Z"));
		Map<String, Object> before = state(restaurant, orderId);

		for (String command : new String[] { "start-preparation", "mark-ready" }) {
			mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/" + command).with(kitchen(restaurant)).with(csrf())
					.header(KEY, UUID.randomUUID().toString())).andExpect(status().isConflict());
		}

		assertEquals(before, state(restaurant, orderId));
	}

	private int perform(UUID restaurant, UUID actor, UUID orderId, UUID key) {
		try {
			return mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor))
					.with(csrf()).header(KEY, key.toString())).andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
