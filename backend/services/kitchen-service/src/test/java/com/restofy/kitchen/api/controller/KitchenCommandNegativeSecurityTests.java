package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Negative security scenarios for the two write commands: wrong verbs, path tricks, header tricks, tenant mix-ups. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenCommandNegativeSecurityTests {

	private static final String KEY = "Idempotency-Key";
	private static final String[] COMMANDS = { "start-preparation", "mark-ready" };

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID accept(UUID restaurant, UUID orderId) {
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		return orderId;
	}

	private Map<String, Object> state(UUID restaurant, UUID orderId) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	private static MockHttpServletRequestBuilder cmd(HttpMethod method, String path) {
		return request(method, path).header(KEY, UUID.randomUUID().toString());
	}

	@Test
	void theSameOrderIdInTwoRestaurantsIsTwoIndependentOrders() throws Exception {
		UUID orderId = UUID.randomUUID();
		UUID restaurantA = UUID.randomUUID();
		UUID restaurantB = UUID.randomUUID();
		accept(restaurantA, orderId);
		accept(restaurantB, orderId);
		Map<String, Object> bBefore = state(restaurantB, orderId);

		mvc.perform(patch("/api/v1/kitchen/orders/" + orderId + "/start-preparation").with(kitchen(restaurantA)).with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isOk());

		assertEquals("PREPARING", state(restaurantA, orderId).get("status"));
		assertEquals(bBefore, state(restaurantB, orderId), "the other restaurant's order with the same id is untouched");
	}

	@Test
	void wrongHttpVerbsOnTheCommandPathsAreRejectedForEveryRoleAndChangeNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant, UUID.randomUUID());
		Map<String, Object> before = state(restaurant, orderId);

		for (String command : COMMANDS) {
			String path = "/api/v1/kitchen/orders/" + orderId + "/" + command;
			for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
				// KITCHEN reaches the controller, where only PATCH exists.
				mvc.perform(cmd(method, path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
			}
			// ADMIN is stopped by the security rule for every write verb, and GET finds no handler either.
			for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
				mvc.perform(cmd(method, path).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
			}
			mvc.perform(cmd(HttpMethod.GET, path).with(admin(restaurant))).andExpect(status().isMethodNotAllowed());
		}
		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void pathTricksNeverReachTheCommandsAndNeverSucceed() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant, UUID.randomUUID());
		Map<String, Object> before = state(restaurant, orderId);
		String base = "/api/v1/kitchen/orders/" + orderId;

		for (String path : List.of(
				base + "/start-preparation/",
				base + "/start-preparation;jsessionid=x",
				"/api/v1/kitchen//orders/" + orderId + "/start-preparation",
				"/api/v1/kitchen/orders/../orders/" + orderId + "/start-preparation",
				"/API/V1/KITCHEN/ORDERS/" + orderId + "/START-PREPARATION",
				"/api/v1/kitchen/orders/" + orderId + "/START-PREPARATION",
				"/api/v1/kitchen/orders/%2e%2e/" + orderId + "/start-preparation")) {
			// ADMIN (read-only) must not get through by any spelling; KITCHEN must not either on a non-existent spelling.
			int adminStatus = mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).with(admin(restaurant)).with(csrf()))
					.andReturn().getResponse().getStatus();
			assertNotEquals(2, adminStatus / 100, "ADMIN must not change state via " + path + " (got " + adminStatus + ")");
		}
		for (String path : List.of(base + "/start-preparation/", base + "/START-PREPARATION")) {
			int kitchenStatus = mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
					.andReturn().getResponse().getStatus();
			assertNotEquals(2, kitchenStatus / 100, "a non-existent spelling must not run the command: " + path);
		}
		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void headerTricksGrantNothingAndAmbiguousIdempotencyKeysAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID orderId = accept(restaurant, UUID.randomUUID());
		Map<String, Object> before = state(restaurant, orderId);
		String path = "/api/v1/kitchen/orders/" + orderId + "/start-preparation";

		// A claimed role or restaurant is not an identity: without authentication it is 401, with another one 404/403.
		mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).header("X-Role", "KITCHEN")
				.header("X-Restaurant-Id", restaurant.toString()).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).header("X-Role", "KITCHEN")
				.header("X-Restaurant-Id", restaurant.toString()).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).header("X-HTTP-Method-Override", "DELETE")
				.header("X-Restaurant-Id", restaurant.toString()).with(kitchen(intruder)).with(csrf())).andExpect(status().isNotFound());

		// Two keys in one request are ambiguous: reject instead of picking one.
		mvc.perform(patch(path).header(KEY, UUID.randomUUID().toString()).header(KEY, UUID.randomUUID().toString())
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		// Oversized or non-UUID keys are bad requests, never a server error.
		for (String bad : List.of("x".repeat(5_000), "00000000-0000-0000-0000-00000000000g", "{}", "null", "../etc/passwd",
				"1-1-1-1-1", "0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22,0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a23",
				" 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22x")) {
			mvc.perform(patch(path).header(KEY, bad).with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		}
		assertEquals(before, state(restaurant, orderId));
	}

	@Test
	void anUppercaseKeyAndItsLowercaseFormAreTheSameCommand() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant, UUID.randomUUID());
		UUID key = UUID.randomUUID();
		String path = "/api/v1/kitchen/orders/" + orderId + "/start-preparation";

		mvc.perform(patch(path).header(KEY, key.toString().toUpperCase()).with(kitchen(restaurant, actor)).with(csrf()))
				.andExpect(status().isOk());
		Map<String, Object> applied = state(restaurant, orderId);
		mvc.perform(patch(path).header(KEY, key.toString().toLowerCase()).with(kitchen(restaurant, actor)).with(csrf()))
				.andExpect(status().isOk());

		assertEquals(applied, state(restaurant, orderId));
	}
}
