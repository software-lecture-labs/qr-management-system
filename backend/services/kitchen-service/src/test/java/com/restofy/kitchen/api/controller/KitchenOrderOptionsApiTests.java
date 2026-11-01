package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.IngestOutcome;
import com.restofy.kitchen.domain.enums.OptionAction;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.option;
import static com.restofy.kitchen.support.OrderMessages.withVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitchenOrderOptionsApiTests {

	private static final String URL = "/api/v1/kitchen/orders/";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private int countOrders(UUID restaurantId) {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE restaurant_id = ?", Integer.class, restaurantId);
	}

	@Test
	void mixedAddAndRemoveOptionsAreShownInOrderWithAmountsAndNotes() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		var soğan = option(OptionAction.REMOVE, "Soğan", 1, null);
		var peynir = option(OptionAction.ADD, "Peynir", 2, "çift kaşar");
		var turşu = option(OptionAction.REMOVE, "Turşu", 1, "alerjisi var");
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "3", "A-3", 1, UUID.randomUUID(),
				item("Burger", 1, "az pişmiş", soğan, peynir, turşu), item("Ayran", 1, null)));

		for (var auth : List.of(kitchen(restaurant), admin(restaurant))) {
			mvc.perform(get(URL + orderId).with(auth))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.items[0].options.length()").value(3))
					.andExpect(jsonPath("$.items[0].options[0].action").value("REMOVE"))
					.andExpect(jsonPath("$.items[0].options[0].name").value("Soğan"))
					.andExpect(jsonPath("$.items[0].options[0].optionId").value(soğan.optionId().toString()))
					.andExpect(jsonPath("$.items[0].options[0].quantity").value(1))
					.andExpect(jsonPath("$.items[0].options[1].action").value("ADD"))
					.andExpect(jsonPath("$.items[0].options[1].name").value("Peynir"))
					.andExpect(jsonPath("$.items[0].options[1].quantity").value(2))
					.andExpect(jsonPath("$.items[0].options[1].note").value("çift kaşar"))
					.andExpect(jsonPath("$.items[0].options[2].action").value("REMOVE"))
					.andExpect(jsonPath("$.items[0].options[2].note").value("alerjisi var"))
					.andExpect(jsonPath("$.items[0].note").value("az pişmiş"))
					// Items without options show an empty list, not a missing field.
					.andExpect(jsonPath("$.items[1].options").isArray())
					.andExpect(jsonPath("$.items[1].options").isEmpty())
					// Kitchen never carries prices.
					.andExpect(jsonPath("$.items[0].price").doesNotExist())
					.andExpect(jsonPath("$.items[0].options[0].priceDelta").doesNotExist());
		}
	}

	@Test
	void storedSnapshotIsVersionedAndRepeatedAcceptanceDoesNotDuplicateOptions() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		OrderAcceptedMessage msg = message(restaurant, orderId, UUID.randomUUID(), "3", "A-3", 1, UUID.randomUUID(),
				item("Burger", 1, null, option(OptionAction.ADD, "Peynir", 1, null)));
		assertEquals(IngestOutcome.CREATED, ingest.ingest(msg));
		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(msg));
		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(withVersion(msg, 1, UUID.randomUUID())));

		assertEquals(1, countOrders(restaurant));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM kitchen_order_items WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals("1", jdbc.queryForObject("""
				SELECT options_snapshot ->> 'schemaVersion' FROM kitchen_order_items WHERE restaurant_id = ?
				""", String.class, restaurant));
		assertEquals(1, jdbc.queryForObject("""
				SELECT jsonb_array_length(options_snapshot -> 'options') FROM kitchen_order_items WHERE restaurant_id = ?
				""", Integer.class, restaurant));
	}

	@Test
	void specialCharactersSurviveStorageAndSerializationUnchanged() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		String name = "Acı \"biber\" \\ ğüşiöç İI ı 🌶️ </script><b>x</b>";
		String note = "satır1\nsatır2\t'tek' \"çift\" & <img src=x onerror=alert(1)>";
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "3", "A-3", 1, UUID.randomUUID(),
				item("Köfte", 1, note, option(OptionAction.ADD, name, 1, note))));

		mvc.perform(get(URL + orderId).with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].note").value(note))
				.andExpect(jsonPath("$.items[0].options[0].name").value(name))
				.andExpect(jsonPath("$.items[0].options[0].note").value(note));
	}

	@Test
	void sizeBoundariesAreAcceptedAtTheLimitAndRejectedBeyondIt() {
		UUID restaurant = UUID.randomUUID();
		var maxOption = option(OptionAction.ADD, "n".repeat(150), 99, "o".repeat(500));
		var maxItem = new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p".repeat(200), 999,
				"x".repeat(1000), List.of(maxOption));
		assertEquals(IngestOutcome.CREATED, ingest.ingest(message(restaurant, UUID.randomUUID(), UUID.randomUUID(), "t".repeat(40),
				"s".repeat(40), 1, UUID.randomUUID(), maxItem)));

		List<OrderAcceptedMessage.Option> fifty = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			fifty.add(option(i % 2 == 0 ? OptionAction.ADD : OptionAction.REMOVE, "m" + i, 1, null));
		}
		assertEquals(IngestOutcome.CREATED, ingest.ingest(message(restaurant, UUID.randomUUID(), UUID.randomUUID(), "1", "A-50",
				1, UUID.randomUUID(), new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "Çok", 1, null, fifty))));
		assertEquals(2, countOrders(restaurant));

		List<OrderAcceptedMessage.Item> tooBig = new ArrayList<>();
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, "x".repeat(1001), List.of()));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1000, null, List.of()));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, null,
				List.of(option(OptionAction.ADD, "n".repeat(151), 1, null))));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, null,
				List.of(option(OptionAction.ADD, "ok", 1, "o".repeat(501)))));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, null,
				List.of(option(OptionAction.ADD, "ok", 100, null))));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, null,
				List.of(option(OptionAction.REMOVE, "ok", 0, null))));
		List<OrderAcceptedMessage.Option> fiftyOne = new ArrayList<>(fifty);
		fiftyOne.add(option(OptionAction.ADD, "extra", 1, null));
		tooBig.add(new OrderAcceptedMessage.Item(UUID.randomUUID(), UUID.randomUUID(), "p", 1, null, fiftyOne));

		for (var bad : tooBig) {
			assertThrows(InvalidOrderMessageException.class, () -> ingest.ingest(message(restaurant, UUID.randomUUID(),
					UUID.randomUUID(), "1", "A-X", 1, UUID.randomUUID(), bad)));
		}
		assertEquals(2, countOrders(restaurant));
	}

	@Test
	void sameOptionAsBothAddAndRemoveIsRejectedAsContradictory() {
		UUID restaurant = UUID.randomUUID();
		UUID sharedOptionId = UUID.randomUUID();
		var add = new OrderAcceptedMessage.Option(OptionAction.ADD, sharedOptionId, "Peynir", 1, null);
		var remove = new OrderAcceptedMessage.Option(OptionAction.REMOVE, sharedOptionId, "Peynir", 1, null);

		assertThrows(InvalidOrderMessageException.class, () -> ingest.ingest(message(restaurant, UUID.randomUUID(),
				UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null, add, remove))));
		assertEquals(0, countOrders(restaurant));
	}

	@Test
	void anotherRestaurantNeverSeesTheOptions() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(owner, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(),
				item("Burger", 1, null, option(OptionAction.REMOVE, "GİZLİ-SOĞAN", 1, null))));

		String body = mvc.perform(get(URL + orderId).with(kitchen(UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		assertFalse(body.contains("GİZLİ-SOĞAN"));
		mvc.perform(get("/api/v1/kitchen/orders?status=WAITING").with(kitchen(UUID.randomUUID())))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void kitchenAndAdminCannotChangeOptionsOrNotes() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		var line = item("Burger", 1, "not", option(OptionAction.ADD, "Peynir", 1, null));
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), line));
		String before = jdbc.queryForObject("SELECT options_snapshot::text FROM kitchen_order_items WHERE restaurant_id = ?",
				String.class, restaurant);

		String[] paths = { URL + orderId, URL + orderId + "/items/" + line.orderItemId(),
				URL + orderId + "/items/" + line.orderItemId() + "/options" };
		String body = "{\"options\":[],\"note\":\"x\"}";
		for (String path : paths) {
			for (var request : List.of(post(path), put(path), patch(path), delete(path))) {
				// KITCHEN reaches the MVC layer, where no such mutation exists; ADMIN is denied every write outright.
				int kitchenCode = mvc.perform(request.with(kitchen(restaurant)).with(csrf()).contentType("application/json").content(body))
						.andReturn().getResponse().getStatus();
				assertEquals(true, kitchenCode == 404 || kitchenCode == 405, "mutation must not exist, got " + kitchenCode + " on " + path);
				int adminCode = mvc.perform(request.with(admin(restaurant)).with(csrf()).contentType("application/json").content(body))
						.andReturn().getResponse().getStatus();
				assertEquals(403, adminCode, "ADMIN must not write on " + path);
			}
		}
		assertEquals(before, jdbc.queryForObject("SELECT options_snapshot::text FROM kitchen_order_items WHERE restaurant_id = ?",
				String.class, restaurant));
		assertEquals("not", jdbc.queryForObject("SELECT note_snapshot FROM kitchen_order_items WHERE restaurant_id = ?",
				String.class, restaurant));
	}
}
