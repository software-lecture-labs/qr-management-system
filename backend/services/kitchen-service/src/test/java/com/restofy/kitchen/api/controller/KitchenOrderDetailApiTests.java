package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.IngestOutcome;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.withVersion;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitchenOrderDetailApiTests {

	private static final String URL = "/api/v1/kitchen/orders/";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void detailShowsOrderTableItemsAndNotesInLineOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		var burger = item("Burger", 2, "az pişmiş");
		var ayran = item("Ayran", 1, null);
		var corba = item("Çorba", 3, "  tuzsuz  ");
		ingest.ingest(message(restaurant, orderId, session, "12", "A-100", 1, UUID.randomUUID(), burger, ayran, corba));

		for (var auth : List.of(kitchen(restaurant), admin(restaurant))) {
			mvc.perform(get(URL + orderId).with(auth))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.orderId").value(orderId.toString()))
					.andExpect(jsonPath("$.orderNumber").value("A-100"))
					.andExpect(jsonPath("$.tableNumber").value("12"))
					.andExpect(jsonPath("$.tableSessionId").value(session.toString()))
					.andExpect(jsonPath("$.status").value("WAITING"))
					.andExpect(jsonPath("$.receivedAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z")))
					.andExpect(jsonPath("$.startedAt").doesNotExist())
					.andExpect(jsonPath("$.readyAt").doesNotExist())
					.andExpect(jsonPath("$.items.length()").value(3))
					.andExpect(jsonPath("$.items[0].productName").value("Burger"))
					.andExpect(jsonPath("$.items[0].quantity").value(2))
					.andExpect(jsonPath("$.items[0].note").value("az pişmiş"))
					.andExpect(jsonPath("$.items[0].orderItemId").value(burger.orderItemId().toString()))
					.andExpect(jsonPath("$.items[0].productId").value(burger.productId().toString()))
					.andExpect(jsonPath("$.items[1].productName").value("Ayran"))
					.andExpect(jsonPath("$.items[1].note").doesNotExist())
					.andExpect(jsonPath("$.items[2].productName").value("Çorba"))
					.andExpect(jsonPath("$.items[2].note").value("  tuzsuz  "));
		}
	}

	@Test
	void notStartedOrNotReadyTimesAndMissingNotesAreExplicitJsonNulls() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(),
				item("Ayran", 1, null)));

		String body = mvc.perform(get(URL + orderId).with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// The keys must be present with a JSON null (not omitted) so clients can rely on a stable shape.
		assertTrue(body.contains("\"startedAt\":null"), body);
		assertTrue(body.contains("\"readyAt\":null"), body);
		assertTrue(body.contains("\"note\":null"), body);
		assertTrue(body.contains("\"options\":[]"), body);
	}

	@Test
	void detailExposesStartAndReadyTimesWhenPresentAndNoInternalFields() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, "READY", Instant.parse("2026-10-17T10:00:00Z"));

		mvc.perform(get(URL + orderId).with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.startedAt").value("2026-10-17T10:01:00Z"))
				.andExpect(jsonPath("$.readyAt").value("2026-10-17T10:02:00Z"))
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.restaurantId").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.sourceEventId").doesNotExist())
				.andExpect(jsonPath("$.sourceOrderVersion").doesNotExist());
	}

	@Test
	void ordersOnTheSameTableAndSessionStaySeparate() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		ingest.ingest(message(restaurant, first, session, "5", "A-1", 1, UUID.randomUUID(), item("Çorba", 1, null)));
		ingest.ingest(message(restaurant, second, session, "5", "A-2", 1, UUID.randomUUID(), item("Pilav", 4, "bol")));

		mvc.perform(get(URL + first).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.orderNumber").value("A-1"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].productName").value("Çorba"));
		mvc.perform(get(URL + second).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.orderNumber").value("A-2"))
				.andExpect(jsonPath("$.items[0].productName").value("Pilav"))
				.andExpect(jsonPath("$.items[0].quantity").value(4))
				.andExpect(jsonPath("$.tableSessionId").value(session.toString()));
	}

	@Test
	void tableSessionKitchenRowAndItemIdsAreNotAcceptedAsOrderIds() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		var line = item("Burger", 1, null);
		ingest.ingest(message(restaurant, orderId, session, "9", "A-9", 1, UUID.randomUUID(), line));
		UUID kitchenRowId = jdbc.queryForObject("SELECT id FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				UUID.class, restaurant, orderId);

		for (UUID wrong : new UUID[] { session, kitchenRowId, line.orderItemId(), line.productId() }) {
			mvc.perform(get(URL + wrong).with(kitchen(restaurant))).andExpect(status().isNotFound());
		}
		mvc.perform(get(URL + orderId).with(kitchen(restaurant))).andExpect(status().isOk());
	}

	@Test
	void storedSnapshotDoesNotChangeWhenLaterVersionsOfTheOrderArrive() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		var original = message(restaurant, orderId, UUID.randomUUID(), "12", "A-100", 5, UUID.randomUUID(),
				item("Burger", 2, "az pişmiş"));
		assertEquals(IngestOutcome.CREATED, ingest.ingest(original));

		var changedNewer = message(restaurant, orderId, UUID.randomUUID(), "99", "ZZ-9", 9, UUID.randomUUID(),
				item("Başka", 7, "yeni"));
		assertEquals(IngestOutcome.NEWER_VERSION_NOT_APPLIED, ingest.ingest(changedNewer));
		assertEquals(IngestOutcome.STALE_IGNORED, ingest.ingest(
				withVersion(changedNewer, 2, UUID.randomUUID())));

		mvc.perform(get(URL + orderId).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.tableNumber").value("12"))
				.andExpect(jsonPath("$.orderNumber").value("A-100"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].productName").value("Burger"))
				.andExpect(jsonPath("$.items[0].quantity").value(2));
	}

	@Test
	void unknownAndForeignOrdersLookIdenticalWith404() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(owner, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		String foreign = mvc.perform(get(URL + orderId).with(kitchen(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andReturn().getResponse().getContentAsString();
		String unknown = mvc.perform(get(URL + UUID.randomUUID()).with(kitchen(other)))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		assertEquals(strip(foreign), strip(unknown));
		assertEquals(false, foreign.contains("Burger"));
	}

	@Test
	void malformedIdAndUnauthorizedCallersAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		mvc.perform(get(URL + "not-a-uuid").with(kitchen(restaurant)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
		mvc.perform(get(URL + orderId)).andExpect(status().isUnauthorized());
		mvc.perform(get(URL + orderId).with(user("waiter").roles("WAITER"))).andExpect(status().isForbidden());
	}

	@Test
	void detailIsReadOnly() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));

		String path = URL + orderId;
		mvc.perform(post(path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		mvc.perform(put(path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		mvc.perform(patch(path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		mvc.perform(delete(path).with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		// Same methods without a CSRF token never reach the controller.
		mvc.perform(post(path).with(kitchen(restaurant))).andExpect(status().isForbidden());
	}

	/** Removes the per-request path from the body so two 404 responses can be compared. */
	private static String strip(String body) {
		return body.replaceAll("\"instance\":\"[^\"]*\"", "");
	}
}
