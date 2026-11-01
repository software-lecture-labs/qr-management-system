package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-04 / 4.2: the stock reference of an OUT_OF_STOCK cancellation request is checked against the order's OWN
 * snapshot (never against another service), and the request history is a readable audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenCancellationStockReferenceApiTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;

	private record Received(UUID orderId, UUID burgerLine, UUID burgerLineTwo, UUID burger, UUID ayranLine, UUID ayran) {
	}

	/** An order with TWO lines of the same product (burger) and one line of another (ayran). */
	private Received receive(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		UUID burger = UUID.randomUUID();
		UUID ayran = UUID.randomUUID();
		UUID lineOne = UUID.randomUUID();
		UUID lineTwo = UUID.randomUUID();
		UUID lineThree = UUID.randomUUID();
		ingest.ingest(new OrderAcceptedMessage(UUID.randomUUID(), 1, Instant.now(), "corr", restaurant, orderId, 1L, "A-1", UUID.randomUUID(),
				"T1",
				List.of(new OrderAcceptedMessage.Item(lineOne, burger, "Burger", 2, null, List.of()),
						new OrderAcceptedMessage.Item(lineTwo, burger, "Burger", 1, "az pişmiş", List.of()),
						new OrderAcceptedMessage.Item(lineThree, ayran, "Ayran", 3, null, List.of()))));
		return new Received(orderId, lineOne, lineTwo, burger, lineThree, ayran);
	}

	private static String stock(UUID product, UUID line) {
		StringBuilder json = new StringBuilder("{\"reasonCode\":\"OUT_OF_STOCK\"");
		if (product != null) {
			json.append(",\"productId\":\"").append(product).append("\"");
		}
		if (line != null) {
			json.append(",\"orderItemId\":\"").append(line).append("\"");
		}
		return json.append("}").toString();
	}

	private ResultActions send(UUID restaurant, UUID actor, UUID orderId, UUID key, String json) throws Exception {
		return mvc.perform(post("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests").header(KEY, key.toString())
				.contentType("application/json").content(json).with(kitchen(restaurant, actor)).with(csrf()));
	}

	private int requests(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	private int events(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = 'KitchenCancellationRequested'",
				Integer.class, restaurant);
	}

	// ---- the reference ---------------------------------------------------------------------------------------------

	@Test
	void aProductOfTheOrderIsAcceptedWithAndWithoutALineAndTheReferenceIsStoredAndSent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);
		UUID key = UUID.randomUUID();

		send(restaurant, UUID.randomUUID(), order.orderId(), key, stock(order.ayran(), order.ayranLine())).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.productId").value(order.ayran().toString()))
						.andExpect(jsonPath("$.orderItemId").value(order.ayranLine().toString()))
				.andExpect(jsonPath("$.reasonCode").value("OUT_OF_STOCK"));

		Map<String, Object> stored = jdbc.queryForMap("SELECT product_id, order_item_id FROM cancellation_requests WHERE restaurant_id = ?",
				restaurant);
		assertEquals(order.ayran(), stored.get("product_id"));
		assertEquals(order.ayranLine(), stored.get("order_item_id"));
		String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE restaurant_id = ? AND event_type = "
				+ "'KitchenCancellationRequested'",
				String.class, restaurant);
		assertTrue(payload.contains(order.ayran().toString()) && payload.contains(order.ayranLine().toString()), payload);
	}

	@Test
	void aProductThatSitsOnTwoLinesIsAcceptedByProductAndByEitherLineButNotByAWrongLine() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);

		// Product only: it is on this order (twice), that is enough.
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(order.burger(), null)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.orderItemId").value((Object) null));
		jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now() WHERE restaurant_id = ?", restaurant);
		// Either burger line is a valid reference.
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(order.burger(), order.burgerLine()))
				.andExpect(status().isAccepted());
		jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now() WHERE restaurant_id = ? AND status = "
				+ "'REQUESTED'", restaurant);
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(order.burger(), order.burgerLineTwo()))
				.andExpect(status().isAccepted());
		jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now() WHERE restaurant_id = ? AND status = "
				+ "'REQUESTED'", restaurant);
		assertEquals(3, requests(restaurant));

		// The ayran line does not carry the burger.
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(order.burger(), order.ayranLine()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STOCK_REFERENCE"));
		assertEquals(3, requests(restaurant));
	}

	@Test
	void anOutOfStockRequestWithoutAProductIsRefusedAndSoIsALineWithoutItsProduct() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);

		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(null, null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STOCK_REFERENCE")).andExpect(jsonPath("$.retryable").value(false));
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), stock(null, order.burgerLine()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STOCK_REFERENCE"));

		assertEquals(0, requests(restaurant));
		assertEquals(0, events(restaurant));
	}

	@Test
	void aProductOrLineThatIsNotPartOfThisOrderIsRefusedWithTheSameGenericAnswerWhereverItComesFrom() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID otherRestaurant = UUID.randomUUID();
		Received mine = receive(restaurant);
		Received sameRestaurantOther = receive(restaurant);
		Received foreign = receive(otherRestaurant);

		String unknown = send(restaurant, UUID.randomUUID(), mine.orderId(), UUID.randomUUID(), stock(UUID.randomUUID(), null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STOCK_REFERENCE")).andReturn().getResponse().getContentAsString();
		String otherOrder = send(restaurant, UUID.randomUUID(), mine.orderId(), UUID.randomUUID(), stock(sameRestaurantOther.burger(), null))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		String otherOrderLine = send(restaurant, UUID.randomUUID(), mine.orderId(), UUID.randomUUID(),
				stock(mine.burger(), sameRestaurantOther.burgerLine()))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		String otherTenant = send(restaurant, UUID.randomUUID(), mine.orderId(), UUID.randomUUID(), stock(foreign.burger(), foreign.burgerLine()))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

		for (String answer : List.of(otherOrder, otherOrderLine, otherTenant)) {
			assertEquals(detail(unknown), detail(answer), "an id that exists elsewhere is answered exactly like one that does not exist");
		}
		assertFalse(otherTenant.contains(foreign.burger().toString()) || otherTenant.contains(otherRestaurant.toString()), otherTenant);
		assertEquals(0, requests(restaurant));
		assertEquals(0, requests(otherRestaurant));
		assertEquals(0, events(restaurant));
	}

	private static String detail(String problem) {
		return JsonPath.read(problem, "$.detail");
	}

	@Test
	void aProductReferenceWithAnyOtherReasonIsRefused() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);

		for (String reason : List.of("CUSTOMER_REQUEST", "KITCHEN_ERROR", "OTHER")) {
			send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(),
					"{\"reasonCode\":\"" + reason + "\",\"productId\":\"" + order.burger() + "\"}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_STOCK_REFERENCE"));
			send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(),
					"{\"reasonCode\":\"" + reason + "\",\"orderItemId\":\"" + order.burgerLine() + "\"}").andExpect(status().isBadRequest());
			// Without a reference those reasons are fine.
			send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), "{\"reasonCode\":\"" + reason + "\"}")
					.andExpect(status().isAccepted());
			jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now() WHERE restaurant_id = ? AND status = "
					+ "'REQUESTED'", restaurant);
		}
		assertEquals(3, requests(restaurant));
	}

	@Test
	void aMalformedReferenceIsABadRequest() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);

		for (String json : List.of("{\"reasonCode\":\"OUT_OF_STOCK\",\"productId\":\"not-a-uuid\"}",
				"{\"reasonCode\":\"OUT_OF_STOCK\",\"productId\":12}",
				"{\"reasonCode\":\"OUT_OF_STOCK\",\"productId\":\"" + order.burger() + "\",\"orderItemId\":\"x\"}")) {
			send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), json).andExpect(status().isBadRequest());
		}
		assertEquals(0, requests(restaurant));
	}

	@Test
	void theSameKeyWithAnotherReferenceIsAConflictAndTheSameReferenceIsARepeat() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		Received order = receive(restaurant);
		UUID key = UUID.randomUUID();
		send(restaurant, actor, order.orderId(), key, stock(order.burger(), order.burgerLine())).andExpect(status().isAccepted());

		send(restaurant, actor, order.orderId(), key, stock(order.burger(), order.burgerLine())).andExpect(status().isOk());
		send(restaurant, actor, order.orderId(), key, stock(order.burger(), null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		send(restaurant, actor, order.orderId(), key, stock(order.ayran(), order.ayranLine())).andExpect(status().isConflict());
		assertEquals(1, requests(restaurant));
		assertEquals(1, events(restaurant));
	}

	// ---- the audit trail -------------------------------------------------------------------------------------------

	@Test
	void theHistoryShowsEveryRequestNewestFirstAndKeepsARequestApartFromTheOrderStatus() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		Received order = receive(restaurant);
		send(restaurant, actor, order.orderId(), UUID.randomUUID(), "{\"reasonCode\":\"OTHER\",\"reasonText\":\"ilk\"}")
				.andExpect(status().isAccepted());
		// The Order service rejected it (written by hand here: the decision flow is a later task).
		jdbc.update("UPDATE cancellation_requests SET status = 'REJECTED', decided_at = now(), decision_reason_code = "
				+ "'ORDER_ALREADY_SERVED' WHERE restaurant_id = ?", restaurant);
		send(restaurant, actor, order.orderId(), UUID.randomUUID(), stock(order.ayran(), order.ayranLine())).andExpect(status().isAccepted());

		mvc.perform(get("/api/v1/kitchen/orders/" + order.orderId() + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].status").value("REQUESTED")).andExpect(jsonPath("$[0].reasonCode").value("OUT_OF_STOCK"))
				.andExpect(jsonPath("$[0].productId").value(order.ayran().toString())).andExpect(jsonPath("$[0].decidedAt").value((Object) null))
				.andExpect(jsonPath("$[1].status").value("REJECTED")).andExpect(jsonPath("$[1].reasonText").value("ilk"))
				.andExpect(jsonPath("$[1].decisionReasonCode").value("ORDER_ALREADY_SERVED")).andExpect(jsonPath("$[1].decidedAt").isNotEmpty());
		// The order itself is not cancelled by any of this.
		assertEquals("WAITING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant));
	}

	@Test
	void theHistoryIsTenantScopedAndReadableByAdminButNotByAnonymousUsers() throws Exception {
		UUID restaurant = UUID.randomUUID();
		Received order = receive(restaurant);
		send(restaurant, UUID.randomUUID(), order.orderId(), UUID.randomUUID(), "{\"reasonCode\":\"OTHER\"}").andExpect(status().isAccepted());
		String path = "/api/v1/kitchen/orders/" + order.orderId() + "/cancellation-requests";

		mvc.perform(get(path).with(admin(restaurant))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
		mvc.perform(get(path).with(kitchen(UUID.randomUUID()))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/kitchen/orders/" + UUID.randomUUID() + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(status().isNotFound());
		// An order without any request has an empty history, not an error.
		Received quiet = receive(restaurant);
		mvc.perform(get("/api/v1/kitchen/orders/" + quiet.orderId() + "/cancellation-requests").with(kitchen(restaurant)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}
}
