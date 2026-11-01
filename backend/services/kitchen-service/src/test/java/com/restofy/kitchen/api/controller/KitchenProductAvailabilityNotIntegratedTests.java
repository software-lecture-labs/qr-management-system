package com.restofy.kitchen.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

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
 * The production wiring today (no Menu adapter exists): a valid command is answered 503 MENU_NOT_INTEGRATED, never a
 * success, and the attempt is on record as unconfirmed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenProductAvailabilityNotIntegratedTests {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void withoutAMenuAdapterNothingIsEverReportedAsChanged() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();

		mvc.perform(patch("/api/v1/kitchen/products/" + UUID.randomUUID() + "/availability").header("Idempotency-Key", key.toString())
				.contentType("application/json").content("{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"OUT_OF_STOCK\"}")
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("MENU_NOT_INTEGRATED")).andExpect(jsonPath("$.retryable").value(false))
				.andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.productId").doesNotExist());

		Map<String, Object> commandRow = jdbc.queryForMap(
				"SELECT * FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
		assertEquals("UNCONFIRMED", commandRow.get("outcome"));
		assertEquals("MENU_NOT_INTEGRATED", commandRow.get("outcome_code"));
		assertNull(commandRow.get("confirmed_at"));
	}

	@Test
	void aTemporaryClosureIsNeverShownAsClosedWithoutAMenuAdapterEither() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();

		mvc.perform(patch("/api/v1/kitchen/products/" + UUID.randomUUID() + "/availability").header("Idempotency-Key", key.toString())
				.contentType("application/json")
				.content("{\"status\":\"TEMPORARILY_CLOSED\"}")
				.with(kitchen(restaurant)).with(csrf())).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("MENU_NOT_INTEGRATED")).andExpect(jsonPath("$.status").value(503));

		Map<String, Object> commandRow = jdbc.queryForMap(
				"SELECT * FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
		assertEquals("TEMPORARILY_CLOSED", commandRow.get("target_status"));
		assertNull(commandRow.get("reason_code"), "no reason was given and none is invented");
		assertNull(commandRow.get("reason_text"));
		assertEquals("UNCONFIRMED", commandRow.get("outcome"));
		assertNull(commandRow.get("confirmed_at"));
	}

	@Test
	void withoutAMenuAdapterTheClosedListIsAnErrorNeverAnEmptyList() throws Exception {
		mvc.perform(get("/api/v1/kitchen/products/closed").with(kitchen(UUID.randomUUID()))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("MENU_NOT_INTEGRATED")).andExpect(jsonPath("$.retryable").value(false))
				.andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.content").doesNotExist())
				.andExpect(jsonPath("$.totalElements").doesNotExist());
	}

	@Test
	void aReopenIsNeverShownAsOpenWithoutAMenuAdapterEither() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();

		mvc.perform(patch("/api/v1/kitchen/products/" + UUID.randomUUID() + "/availability").header("Idempotency-Key", key.toString())
				.contentType("application/json").content("{\"status\":\"AVAILABLE\"}").with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MENU_NOT_INTEGRATED"));

		Map<String, Object> commandRow = jdbc.queryForMap(
				"SELECT * FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
		assertEquals("AVAILABLE", commandRow.get("target_status"));
		assertEquals("UNCONFIRMED", commandRow.get("outcome"));
		assertNull(commandRow.get("confirmed_at"));
	}
}
