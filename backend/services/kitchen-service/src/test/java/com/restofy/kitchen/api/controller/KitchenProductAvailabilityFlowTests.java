package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import com.restofy.kitchen.support.FakeMenuCatalogue;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-03 / 3.3: close - reopen - close and the closed-products list against a stateful stand-in for the Menu
 * service (see {@link FakeMenuCatalogue}). Proves Kitchen's behaviour towards the proposed contract only; a real Menu
 * service is not integrated.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenProductAvailabilityFlowTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class MenuStandIn {
		@Bean
		@Primary
		FakeMenuCatalogue fakeMenuCatalogue() {
			return new FakeMenuCatalogue();
		}
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private FakeMenuCatalogue menu;

	private ResultActions command(UUID restaurant, UUID actor, UUID product, UUID key, String json) throws Exception {
		return mvc.perform(patch("/api/v1/kitchen/products/" + product + "/availability").header("Idempotency-Key", key.toString())
				.contentType("application/json").content(json).with(kitchen(restaurant, actor)).with(csrf()));
	}

	private ResultActions closedList(UUID restaurant, String query) throws Exception {
		return mvc.perform(get("/api/v1/kitchen/products/closed" + query).with(kitchen(restaurant)));
	}

	private Map<String, Object> row(UUID restaurant, UUID key) {
		return jdbc.queryForMap("SELECT * FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
	}

	private int rows(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	@Test
	void closeReopenCloseKeepsEveryCommandOnRecordAndTheListFollowsMenu() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Burger", true, true);
		UUID closeOne = UUID.randomUUID();
		UUID reopen = UUID.randomUUID();
		UUID closeTwo = UUID.randomUUID();

		command(restaurant, actor, product, closeOne, "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"STOCK\",\"reasonText\":\"Bitti\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		closedList(restaurant, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].productId").value(product.toString()))
				.andExpect(jsonPath("$.content[0].productName").value("Burger"))
				.andExpect(jsonPath("$.content[0].status").value("OUT_OF_STOCK"))
				.andExpect(jsonPath("$.content[0].reasonCode").value("STOCK"))
				.andExpect(jsonPath("$.content[0].reasonText").value("Bitti"))
				.andExpect(jsonPath("$.content[0].changedByUserId").value(actor.toString()))
				.andExpect(jsonPath("$.content[0].published").value(true));

		command(restaurant, actor, product, reopen, "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.reasonCode").value((Object) null)).andExpect(jsonPath("$.published").value(true));
		closedList(restaurant, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.content").isEmpty());

		command(restaurant, actor, product, closeTwo,
				"{\"status\":\"TEMPORARILY_CLOSED\",\"reasonText\":\"Fritöz bozuldu\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(3));
		closedList(restaurant, "").andExpect(jsonPath("$.content[0].status").value("TEMPORARILY_CLOSED"))
				.andExpect(jsonPath("$.content[0].reasonCode").value((Object) null))
				.andExpect(jsonPath("$.content[0].reasonText").value("Fritöz bozuldu"));

		// Kitchen's own log keeps all three commands; reopening did not erase why the product was closed first.
		assertEquals(3, rows(restaurant));
		Map<String, Object> first = row(restaurant, closeOne);
		assertEquals("OUT_OF_STOCK", first.get("target_status"));
		assertEquals("STOCK", first.get("reason_code"));
		assertEquals("Bitti", first.get("reason_text"));
		assertEquals("CONFIRMED", first.get("outcome"));
		assertEquals("AVAILABLE", row(restaurant, reopen).get("target_status"));
		assertNull(row(restaurant, reopen).get("reason_code"));
		assertEquals("TEMPORARILY_CLOSED", row(restaurant, closeTwo).get("target_status"));
		assertEquals(ProductAvailabilityStatus.TEMPORARILY_CLOSED, menu.statusOf(product));
	}

	@Test
	void reopeningNeverPublishesAProductAndTheAnswerShowsTheDifference() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Taslak ürün", false, true);

		command(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), "{\"status\":\"OUT_OF_STOCK\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.published").value(false));
		closedList(restaurant, "").andExpect(jsonPath("$.content[0].published").value(false));
		command(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), "{\"status\":\"AVAILABLE\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.published").value(false));
	}

	@Test
	void anotherRestaurantNeitherSeesNorReopensAProduct() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID product = menu.addProduct(owner, "Burger", true, true);
		command(owner, UUID.randomUUID(), product, UUID.randomUUID(), "{\"status\":\"OUT_OF_STOCK\"}").andExpect(status().isOk());

		closedList(intruder, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
		UUID key = UUID.randomUUID();
		command(intruder, UUID.randomUUID(), product, key, "{\"status\":\"AVAILABLE\"}").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

		assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(product));
		closedList(owner, "").andExpect(jsonPath("$.totalElements").value(1));
		assertEquals("REJECTED", row(intruder, key).get("outcome"));
	}

	@Test
	void aPassiveCatalogueProductCannotBeChangedAndIsNotListed() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Eski ürün", true, false);
		UUID key = UUID.randomUUID();

		command(restaurant, UUID.randomUUID(), product, key, "{\"status\":\"AVAILABLE\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE")).andExpect(jsonPath("$.retryable").value(false))
				.andExpect(header().doesNotExist("Retry-After"));

		Map<String, Object> commandRow = row(restaurant, key);
		assertEquals("REJECTED", commandRow.get("outcome"));
		assertEquals("PRODUCT_INACTIVE", commandRow.get("outcome_code"));
		closedList(restaurant, "").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void aRepeatedReopenIsAskedFromMenuAgainAndNeverCreatesANewChange() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Burger", true, true);
		command(restaurant, actor, product, UUID.randomUUID(), "{\"status\":\"OUT_OF_STOCK\"}").andExpect(status().isOk());
		UUID reopen = UUID.randomUUID();

		command(restaurant, actor, product, reopen, "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));
		command(restaurant, actor, product, reopen, "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));

		assertEquals(2L, menu.versionOf(product), "the repeat was answered by Menu without a second change");
		assertEquals(2, row(restaurant, reopen).get("attempt_count"));
		assertEquals(2, rows(restaurant));
		// The same key for a different intent is a conflict and never reaches Menu.
		command(restaurant, actor, product, reopen, "{\"status\":\"OUT_OF_STOCK\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		assertEquals(2L, menu.versionOf(product));
	}

	@Test
	void theListFiltersByStatusAndPagesDeterministicallyNewestClosingFirst() throws Exception {
		UUID restaurant = UUID.randomUUID();
		List<UUID> closedInOrder = new ArrayList<>();
		List<UUID> outOfStock = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			UUID product = menu.addProduct(restaurant, "Ürün " + i, true, true);
			boolean stock = i % 2 == 0;
			command(restaurant, UUID.randomUUID(), product, UUID.randomUUID(),
					stock ? "{\"status\":\"OUT_OF_STOCK\"}" : "{\"status\":\"TEMPORARILY_CLOSED\"}").andExpect(status().isOk());
			closedInOrder.add(0, product);
			if (stock) {
				outOfStock.add(0, product);
			}
		}

		List<String> seen = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			String body = closedList(restaurant, "?size=2&page=" + page).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
					.andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3))
					.andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(2))
					.andReturn().getResponse().getContentAsString();
			for (UUID id : closedInOrder) {
				if (body.contains(id.toString())) {
					seen.add(id.toString());
				}
			}
		}
		assertEquals(closedInOrder.stream().map(UUID::toString).toList(), seen, "newest first, every product exactly once");
		closedList(restaurant, "?size=2&page=3").andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(5));

		closedList(restaurant, "?status=OUT_OF_STOCK").andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].productId").value(outOfStock.get(0).toString()))
				.andExpect(jsonPath("$.content[0].status").value("OUT_OF_STOCK"));
		closedList(restaurant, "?status=TEMPORARILY_CLOSED").andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void adminMayReadTheClosedListButNeverChangeAnything() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = menu.addProduct(restaurant, "Burger", true, true);
		command(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), "{\"status\":\"OUT_OF_STOCK\"}").andExpect(status().isOk());

		mvc.perform(get("/api/v1/kitchen/products/closed").with(admin(restaurant))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(patch("/api/v1/kitchen/products/" + product + "/availability").header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType("application/json").content("{\"status\":\"AVAILABLE\"}").with(admin(restaurant)).with(csrf()))
				.andExpect(status().isForbidden());

		assertEquals(ProductAvailabilityStatus.OUT_OF_STOCK, menu.statusOf(product));
		assertEquals(1, rows(restaurant));
	}
}
