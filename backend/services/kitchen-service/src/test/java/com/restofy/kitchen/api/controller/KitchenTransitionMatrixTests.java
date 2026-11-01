package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The complete transition matrix of docs/kitchen-02-contract.md §5: every source status against both commands, each
 * with a NEW command id (a verified repeat is covered separately). A rejected command must leave no trace at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenTransitionMatrixTests {

	private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	/** source status, command path, expected HTTP status, expected status afterwards. */
	static Stream<Arguments> matrix() {
		return Stream.of(
				Arguments.of("WAITING", "start-preparation", 200, "PREPARING"),
				Arguments.of("WAITING", "mark-ready", 409, "WAITING"),
				Arguments.of("PREPARING", "start-preparation", 409, "PREPARING"),
				Arguments.of("PREPARING", "mark-ready", 200, "READY"),
				Arguments.of("READY", "start-preparation", 409, "READY"),
				Arguments.of("READY", "mark-ready", 409, "READY"),
				Arguments.of("CANCELLED", "start-preparation", 409, "CANCELLED"),
				Arguments.of("CANCELLED", "mark-ready", 409, "CANCELLED"));
	}

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version, o.cancelled_at,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	@ParameterizedTest(name = "{0} + {1} -> HTTP {2}, order is {3}")
	@MethodSource("matrix")
	void everySourceStatusAndCommandBehavesExactlyAsDocumented(String source, String command, int http, String after)
			throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, source, T0);
		Map<String, Object> before = state(restaurant, orderId);

		var result = mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/" + command)
				.with(kitchen(restaurant)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString()));

		result.andExpect(status().is(http));
		Map<String, Object> now = state(restaurant, orderId);
		assertEquals(after, now.get("status"));
		if (http == 409) {
			result.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
					.andExpect(jsonPath("$.currentStatus").value(source))
					.andExpect(jsonPath("$.retryable").value(false))
					.andExpect(jsonPath("$.trace").doesNotExist());
			assertEquals(before, now, "a rejected command must change nothing (status, version, times, history)");
		} else {
			result.andExpect(jsonPath("$.status").value(after));
			assertEquals(((Long) before.get("version")) + 1, now.get("version"));
			assertEquals(((Long) before.get("history")) + 1, now.get("history"));
		}
	}

	@Test
	void anAppliedCommandRepeatedWithTheSameIdIsA200ForBothTransitionsAndChangesNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, "WAITING", T0);
		UUID startKey = UUID.randomUUID();
		UUID readyKey = UUID.randomUUID();

		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", startKey.toString())).andExpect(status().isOk());
		Map<String, Object> preparing = state(restaurant, orderId);
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", startKey.toString())).andExpect(status().isOk());
		assertEquals(preparing, state(restaurant, orderId));

		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", readyKey.toString())).andExpect(status().isOk());
		Map<String, Object> ready = state(restaurant, orderId);
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", readyKey.toString())).andExpect(status().isOk());
		assertEquals(ready, state(restaurant, orderId));
	}

	@Test
	void theStartCommandsRepeatStopsBeingA200OnceTheOrderMovedOnAndSaysSoExplicitly() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, "WAITING", T0);
		UUID startKey = UUID.randomUUID();
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", startKey.toString())).andExpect(status().isOk());
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/mark-ready").with(kitchen(restaurant, actor)).with(csrf())
				.header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
		Map<String, Object> ready = state(restaurant, orderId);

		// Idempotency does not replay history: the original start succeeded, but the order is READY now.
		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurant, actor)).with(csrf())
						.header("Idempotency-Key", startKey.toString()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
				.andExpect(jsonPath("$.currentStatus").value("READY"));
		assertEquals(ready, state(restaurant, orderId));
	}
}
