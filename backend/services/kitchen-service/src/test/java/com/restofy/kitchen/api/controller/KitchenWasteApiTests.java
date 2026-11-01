package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import org.hamcrest.Matchers;
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
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** KITCHEN-03 / 3.4: manual waste entries (POST/GET /api/v1/kitchen/waste) against a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenWasteApiTests {

	private static final String PATH = "/api/v1/kitchen/waste";
	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;

	// ---- helpers -------------------------------------------------------------------------------------------------

	private static String body(UUID product, String quantity, String reason, String description, UUID orderId) {
		StringBuilder json = new StringBuilder("{\"productId\":\"").append(product).append("\",\"quantity\":").append(quantity)
				.append(",\"reasonCode\":\"").append(reason).append("\"");
		if (description != null) {
			json.append(",\"description\":\"").append(description).append("\"");
		}
		if (orderId != null) {
			json.append(",\"orderId\":\"").append(orderId).append("\"");
		}
		return json.append("}").toString();
	}

	private static String body(UUID product) {
		return body(product, "2", "SPOILAGE", null, null);
	}

	private static MockHttpServletRequestBuilder entry(UUID key, String json) {
		return post(PATH).header(KEY, key.toString()).contentType("application/json").content(json);
	}

	private ResultActions send(UUID restaurant, UUID actor, UUID key, String json) throws Exception {
		return mvc.perform(entry(key, json).with(kitchen(restaurant, actor)).with(csrf()));
	}

	private int rows(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM waste_records WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	private Map<String, Object> row(UUID restaurant, UUID key) {
		return jdbc.queryForMap("SELECT * FROM waste_records WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
	}

	/** An order the kitchen received; returns {orderId, productId} so the product's name is one Kitchen really knows. */
	private UUID[] receiveOrder(UUID restaurant, String productName) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item(productName, 1, null)));
		UUID productId = jdbc.queryForObject("""
				SELECT i.product_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ?
				""", UUID.class, restaurant, orderId);
		return new UUID[] { orderId, productId };
	}

	private void insertAt(UUID restaurant, UUID product, int quantity, String reason, Instant recordedAt) {
		jdbc.update("""
				INSERT INTO waste_records (id, restaurant_id, command_id, product_id, quantity, reason_code, recorded_at, recorded_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), restaurant, UUID.randomUUID(), product, quantity, reason, Timestamp.from(recordedAt), UUID.randomUUID());
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

	// ---- recording -----------------------------------------------------------------------------------------------

	@Test
	void anEntryIsStoredWithTheServersTimeAndTheVerifiedUserAndAnswers201() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		Instant before = Instant.now();

		send(restaurant, actor, key, body(product, "3", "DROPPED_OR_DAMAGED", "Tepsi düştü", null)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.productId").value(product.toString())).andExpect(jsonPath("$.quantity").value(3))
				.andExpect(jsonPath("$.reasonCode").value("DROPPED_OR_DAMAGED")).andExpect(jsonPath("$.description").value("Tepsi düştü"))
				.andExpect(jsonPath("$.orderId").value((Object) null)).andExpect(jsonPath("$.productName").value((Object) null))
				.andExpect(jsonPath("$.recordedByUserId").value(actor.toString())).andExpect(jsonPath("$.id").isNotEmpty());

		Map<String, Object> stored = row(restaurant, key);
		assertEquals(3, stored.get("quantity"));
		assertEquals(actor, stored.get("recorded_by_user_id"));
		Instant recordedAt = ((Timestamp) stored.get("recorded_at")).toInstant();
		assertFalse(recordedAt.isBefore(before.minusSeconds(1)) || recordedAt.isAfter(Instant.now().plusSeconds(1)), "the server's own time");
		assertNull(stored.get("product_name_snapshot"), "no name Kitchen knows, so none is invented");
	}

	@Test
	void everyReasonIsAcceptedAndNothingElse() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		for (String reason : List.of("WRONG_PREPARATION", "SPOILAGE", "DROPPED_OR_DAMAGED", "WASTE_AFTER_CANCELLATION", "OTHER")) {
			send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", reason, null, null)).andExpect(status().isCreated())
					.andExpect(jsonPath("$.reasonCode").value(reason));
		}
		for (String reason : List.of("spoilage", "BURNT", "", "WASTE", "null")) {
			send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", reason, null, null)).andExpect(status().isBadRequest());
		}
		assertEquals(5, rows(restaurant));
	}

	@Test
	void theProductNameIsOnlyOneKitchenReceivedWithAnOrderOfThisRestaurant() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID[] mine = receiveOrder(restaurant, "Köfte");
		UUID[] foreign = receiveOrder(other, "Başkasının Ürünü");

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(mine[1])).andExpect(status().isCreated())
				.andExpect(jsonPath("$.productName").value("Köfte"));
		// The product id of another restaurant's order tells this restaurant nothing, and no name is made up.
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(foreign[1])).andExpect(status().isCreated())
				.andExpect(jsonPath("$.productName").value((Object) null));
	}

	@Test
	void invalidBodiesAreBadRequestsAndNothingIsStored() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		List<String> bad = List.of(
				body(product, "0", "SPOILAGE", null, null),
				body(product, "-1", "SPOILAGE", null, null),
				body(product, "10001", "SPOILAGE", null, null),
				body(product, "1.5", "SPOILAGE", null, null),
				body(product, "\\\"2\\\"", "SPOILAGE", null, null),
				body(product, "null", "SPOILAGE", null, null),
				body(product, "99999999999", "SPOILAGE", null, null),
				"{\"quantity\":1,\"reasonCode\":\"SPOILAGE\"}",
				"{\"productId\":\"not-a-uuid\",\"quantity\":1,\"reasonCode\":\"SPOILAGE\"}",
				"{\"productId\":\"" + product + "\",\"quantity\":1}",
				body(product, "1", "SPOILAGE", "x".repeat(501), null),
				body(product, "1", "SPOILAGE", "bell\\u0007", null),
				body(product, "1", "SPOILAGE", "line\\nbreak", null),
				body(product, "1", "SPOILAGE", null, null).replace("}", ",\"orderId\":\"nope\"}"),
				"{\"productId\":", "[]", "", "not json");
		for (String json : bad) {
			send(restaurant, UUID.randomUUID(), UUID.randomUUID(), json).andExpect(status().isBadRequest());
		}
		mvc.perform(post(PATH).header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		assertEquals(0, rows(restaurant));
	}

	@Test
	void theBoundsOfAnEntryAreAccepted() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", "OTHER", "ş".repeat(500), null))
				.andExpect(status().isCreated());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "10000", "OTHER", "   ", null)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value((Object) null));
		assertEquals(2, rows(restaurant));
	}

	@Test
	void restaurantUserTimeAndStockAreNeverTakenFromTheClient() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		String forged = body(product).replace("}", ",\"restaurantId\":\"" + UUID.randomUUID() + "\",\"recordedByUserId\":\""
				+ UUID.randomUUID() + "\",\"recordedAt\":\"1999-01-01T00:00:00Z\",\"productName\":\"Sahte Ad\",\"stock\":-5}");

		mvc.perform(entry(key, forged).header("X-Restaurant-Id", UUID.randomUUID().toString()).with(kitchen(restaurant, actor)).with(csrf()))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.recordedByUserId").value(actor.toString()))
				.andExpect(jsonPath("$.productName").value((Object) null));

		Map<String, Object> stored = row(restaurant, key);
		assertEquals(actor, stored.get("recorded_by_user_id"));
		assertTrue(((Timestamp) stored.get("recorded_at")).toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
		assertEquals(1, rows(restaurant));
	}

	@Test
	void anEntryNeverTouchesOrdersEventsOrAvailability() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID[] received = receiveOrder(restaurant, "Burger");
		String snapshot = """
				SELECT o.status, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e WHERE e.restaurant_id = o.restaurant_id) AS events,
				       (SELECT count(*) FROM product_availability_commands c WHERE c.restaurant_id = o.restaurant_id) AS availability
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""";
		Map<String, Object> before = jdbc.queryForMap(snapshot, restaurant, received[0]);

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(received[1], "5", "WASTE_AFTER_CANCELLATION", null, received[0]))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.orderId").value(received[0].toString()));

		assertEquals(before, jdbc.queryForMap(snapshot, restaurant, received[0]));
	}

	@Test
	void anOrderLinkMustBeAnOrderOfTheCallersOwnRestaurant() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID[] mine = receiveOrder(restaurant, "Burger");
		UUID[] foreign = receiveOrder(other, "Burger");
		UUID product = UUID.randomUUID();

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", "SPOILAGE", null, mine[0])).andExpect(status().isCreated());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", "SPOILAGE", null, foreign[0])).andExpect(status().isNotFound());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), body(product, "1", "SPOILAGE", null, UUID.randomUUID()))
				.andExpect(status().isNotFound());
		assertEquals(1, rows(restaurant));
		assertEquals(0, rows(other));
	}

	// ---- security ------------------------------------------------------------------------------------------------

	@Test
	void onlyAnAuthenticatedKitchenUserWithCsrfMayRecordAndWrongVerbsAreRefused() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();

		mvc.perform(entry(UUID.randomUUID(), body(product)).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(entry(UUID.randomUUID(), body(product)).header("X-Role", "KITCHEN").header("X-Restaurant-Id", restaurant.toString())
				.with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(entry(UUID.randomUUID(), body(product)).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(entry(UUID.randomUUID(), body(product)).with(kitchen(restaurant))).andExpect(status().isForbidden());
		for (var request : List.of(put(PATH), patch(PATH), delete(PATH))) {
			mvc.perform(request.header(KEY, UUID.randomUUID().toString()).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
			mvc.perform(request.header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().isMethodNotAllowed());
		}
		assertEquals(0, rows(restaurant));
	}

	// ---- Idempotency-Key -----------------------------------------------------------------------------------------

	@Test
	void theIdempotencyKeyIsMandatoryAndACanonicalUuid() throws Exception {
		UUID restaurant = UUID.randomUUID();
		String json = body(UUID.randomUUID());

		mvc.perform(post(PATH).contentType("application/json").content(json).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(post(PATH).contentType("application/json").content(json).header(KEY, UUID.randomUUID().toString())
				.header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		for (String bad : List.of("not-a-uuid", "{}", "null", "x".repeat(5_000), " 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22x")) {
			mvc.perform(post(PATH).contentType("application/json").content(json).header(KEY, bad).with(kitchen(restaurant)).with(csrf()))
					.andExpect(status().isBadRequest());
		}
		assertEquals(0, rows(restaurant));
	}

	@Test
	void theSameEntryAgainIsAnsweredFromTheStoredOneAndNeverStoredTwice() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		String json = body(product, "4", "SPOILAGE", "Bozuldu", null);

		String first = send(restaurant, actor, key, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		// The uppercase spelling of the key is the same command.
		String repeat = mvc.perform(post(PATH).header(KEY, key.toString().toUpperCase()).contentType("application/json").content(json)
				.with(kitchen(restaurant, actor)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(first, repeat, "the very same stored entry");
		assertEquals(1, rows(restaurant));
	}

	@Test
	void theSameKeyForAnotherEntryIsAConflictAndOtherRestaurantsAreIndependent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		send(restaurant, actor, key, body(product, "4", "SPOILAGE", "Bozuldu", null)).andExpect(status().isCreated());

		for (String different : List.of(body(UUID.randomUUID(), "4", "SPOILAGE", "Bozuldu", null),
				body(product, "5", "SPOILAGE", "Bozuldu", null),
				body(product, "4", "OTHER", "Bozuldu", null), body(product, "4", "SPOILAGE", "Başka", null),
						body(product, "4", "SPOILAGE", null, null))) {
			send(restaurant, actor, key, different).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		}
		send(restaurant, UUID.randomUUID(), key, body(product, "4", "SPOILAGE", "Bozuldu", null)).andExpect(status().isConflict());
		assertEquals(1, rows(restaurant));

		UUID otherRestaurant = UUID.randomUUID();
		send(otherRestaurant, actor, key, body(product, "9", "OTHER", null, null)).andExpect(status().isCreated());
		assertEquals(1, rows(otherRestaurant));
	}

	@Test
	void parallelRequestsWithTheSameKeyStoreExactlyOneEntry() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		String json = body(UUID.randomUUID(), "2", "SPOILAGE", null, null);

		for (int round = 0; round < 3; round++) {
			UUID key = UUID.randomUUID();
			List<Integer> statuses = fire(8, n -> entry(key, json).with(kitchen(restaurant, actor)).with(csrf()));

			assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exactly one request created it: " + statuses);
			assertEquals(7, statuses.stream().filter(s -> s == 200).count(), "the others were verified repeats: " + statuses);
		}
		assertEquals(3, rows(restaurant));
	}

	// ---- listing -------------------------------------------------------------------------------------------------

	@Test
	void theListIsNewestFirstTenantScopedAndFilteredByTimeAndProduct() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID burger = UUID.randomUUID();
		UUID pizza = UUID.randomUUID();
		Instant t0 = Instant.parse("2026-10-20T10:00:00Z");
		insertAt(restaurant, burger, 1, "SPOILAGE", t0);
		insertAt(restaurant, pizza, 2, "OTHER", t0.plusSeconds(60));
		insertAt(restaurant, burger, 3, "WRONG_PREPARATION", t0.plusSeconds(120));
		insertAt(other, burger, 99, "OTHER", t0.plusSeconds(30));

		mvc.perform(get(PATH).with(kitchen(restaurant))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].quantity").value(3)).andExpect(jsonPath("$.content[1].quantity").value(2))
				.andExpect(jsonPath("$.content[2].quantity").value(1))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
		mvc.perform(get(PATH + "?productId=" + burger).with(admin(restaurant))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));
		// [from, to): the lower bound is inside, the upper bound is outside.
		mvc.perform(get(PATH + "?recordedFrom=" + t0.plusSeconds(60) + "&recordedTo=" + t0.plusSeconds(120)).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].quantity").value(2));
		mvc.perform(get(PATH + "?recordedFrom=" + t0.plusSeconds(120)).with(kitchen(restaurant))).andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get(PATH + "?size=2&page=1").with(kitchen(restaurant))).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].quantity").value(1));
		mvc.perform(get(PATH).with(kitchen(UUID.randomUUID()))).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void badListParametersAreBadRequestsAndTheListIsForKitchenAndAdminOnly() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String query : List.of("?recordedFrom=", "?recordedTo=", "?recordedFrom=yesterday", "?recordedFrom=2026-10-20", "?productId=",
				"?productId=abc", "?page=", "?page=-1", "?page=x", "?size=", "?size=0", "?size=101", "?size=x",
				"?recordedFrom=2026-10-20T10:00:00Z&recordedTo=2026-10-20T10:00:00Z",
				"?recordedFrom=2026-10-20T11:00:00Z&recordedTo=2026-10-20T10:00:00Z")) {
			mvc.perform(get(PATH + query).with(kitchen(restaurant))).andExpect(status().isBadRequest());
		}
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH).header("X-Restaurant-Id", restaurant.toString())).andExpect(status().isUnauthorized());
	}
}
