package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.application.contract.ProductAvailabilityState;
import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.application.port.MenuAvailabilityPort.Kind;
import com.restofy.kitchen.application.port.MenuAvailabilityPort.Result;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntFunction;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-03 / 3.1: PATCH /api/v1/kitchen/products/{productId}/availability (OUT_OF_STOCK) against a real PostgreSQL
 * and a programmable stand-in for the Menu service. The Menu service itself is not integrated yet: nothing here
 * proves a live Menu call, only that Kitchen behaves correctly towards the port.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenProductAvailabilityApiTests {

	private static final String KEY = "Idempotency-Key";
	private static final String MENU_TIME = "2026-10-20T09:00:00Z";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@MockitoBean
	private MenuAvailabilityPort menu;

	// ---- helpers -------------------------------------------------------------------------------------------------

	private static String body(String reasonCode, String reasonText) {
		return reasonText == null
				? "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"" + reasonCode + "\"}"
				: "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"" + reasonCode + "\",\"reasonText\":\"" + reasonText + "\"}";
	}

	private static String body() {
		return body("OUT_OF_STOCK", null);
	}

	private static String closeBody(String reasonCode, String reasonText) {
		return body(reasonCode, reasonText).replace("OUT_OF_STOCK\",\"reasonCode", "TEMPORARILY_CLOSED\",\"reasonCode");
	}

	private static String path(UUID productId) {
		return "/api/v1/kitchen/products/" + productId + "/availability";
	}

	private static MockHttpServletRequestBuilder change(UUID productId, UUID key, String json) {
		return patch(path(productId)).header(KEY, key.toString()).contentType("application/json").content(json);
	}

	private ResultActions send(UUID restaurant, UUID actor, UUID productId, UUID key, String json) throws Exception {
		return mvc.perform(change(productId, key, json).with(kitchen(restaurant, actor)).with(csrf()));
	}

	private static ProductAvailabilityState stateOf(MenuAvailabilityCommand c) {
		return new ProductAvailabilityState(c.productId(), c.targetStatus(), c.reasonCode(), c.reasonText(),
				Instant.parse(MENU_TIME), c.requestedByUserId(), 3L, true);
	}

	private void menuConfirmsEverything() {
		doAnswer(call -> Result.applied(stateOf(call.getArgument(0)))).when(menu).change(any());
	}

	private void menuAnswers(Result result) {
		doReturn(result).when(menu).change(any());
	}

	private Map<String, Object> row(UUID restaurant, UUID key) {
		return jdbc.queryForMap("SELECT * FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?", restaurant, key);
	}

	private int rows(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ?", Integer.class, restaurant);
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

	// ---- success -------------------------------------------------------------------------------------------------

	@Test
	void outOfStockIsReportedExactlyAsMenuConfirmedItAndTheCommandIsRecorded() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, body("INGREDIENT_SHORTAGE", "Domates bitti")).andExpect(status().isOk())
				.andExpect(jsonPath("$.productId").value(product.toString()))
				.andExpect(jsonPath("$.status").value("OUT_OF_STOCK"))
				.andExpect(jsonPath("$.reasonCode").value("INGREDIENT_SHORTAGE"))
				.andExpect(jsonPath("$.reasonText").value("Domates bitti"))
				.andExpect(jsonPath("$.changedAt").value(MENU_TIME))
				.andExpect(jsonPath("$.changedByUserId").value(actor.toString()))
				.andExpect(jsonPath("$.version").value(3));

		ArgumentCaptor<MenuAvailabilityCommand> sent = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
		verify(menu).change(sent.capture());
		assertEquals(new MenuAvailabilityCommand(key, restaurant, product, ProductAvailabilityStatus.OUT_OF_STOCK,
				"INGREDIENT_SHORTAGE", "Domates bitti", actor), sent.getValue());

		Map<String, Object> commandRow = row(restaurant, key);
		assertEquals("CONFIRMED", commandRow.get("outcome"));
		assertNull(commandRow.get("outcome_code"));
		assertEquals(1, commandRow.get("attempt_count"));
		assertNotNull(commandRow.get("confirmed_at"));
		assertEquals(product, commandRow.get("product_id"));
		assertEquals(actor, commandRow.get("requested_by_user_id"));
		assertEquals("OUT_OF_STOCK", commandRow.get("target_status"));
		assertEquals("Domates bitti", commandRow.get("reason_text"));
	}

	@Test
	void restaurantUserAndTimeFromTheClientAreIgnoredAndOnlyThePrincipalCounts() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();
		String forged = "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"OUT_OF_STOCK\",\"restaurantId\":\"" + UUID.randomUUID()
				+ "\",\"requestedByUserId\":\"" + UUID.randomUUID() + "\",\"changedAt\":\"1999-01-01T00:00:00Z\",\"version\":99}";

		mvc.perform(change(product, key, forged).header("X-Restaurant-Id", UUID.randomUUID().toString())
				.header("X-User-Id", UUID.randomUUID().toString()).with(kitchen(restaurant, actor)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.changedAt").value(MENU_TIME))
				.andExpect(jsonPath("$.changedByUserId").value(actor.toString())).andExpect(jsonPath("$.version").value(3));

		ArgumentCaptor<MenuAvailabilityCommand> sent = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
		verify(menu).change(sent.capture());
		assertEquals(restaurant, sent.getValue().restaurantId());
		assertEquals(actor, sent.getValue().requestedByUserId());
		assertEquals(1, rows(restaurant));
	}

	@Test
	void aBlankReasonTextIsTheSameAsNoReasonText() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, body("OUT_OF_STOCK", "   ")).andExpect(status().isOk())
				.andExpect(jsonPath("$.reasonText").value((Object) null));
		assertNull(row(restaurant, key).get("reason_text"));
		// Omitting the text is the same command, not a conflict.
		send(restaurant, actor, product, key, body()).andExpect(status().isOk());
		assertEquals(1, rows(restaurant));
	}

	// ---- request validation --------------------------------------------------------------------------------------

	@Test
	void invalidBodiesAreBadRequestsThatNeverReachMenuAndLeaveNoRecord() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		List<String> bad = List.of(
				"{\"reasonCode\":\"OUT_OF_STOCK\"}",
				"{\"status\":null,\"reasonCode\":\"OUT_OF_STOCK\"}",
				"{\"status\":\"BROKEN\",\"reasonCode\":\"OUT_OF_STOCK\"}",
				"{\"status\":\"out_of_stock\",\"reasonCode\":\"OUT_OF_STOCK\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"lowercase\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"A\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"BAD CODE\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"" + "A".repeat(41) + "\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"OUT_OF_STOCK\",\"reasonText\":\"" + "x".repeat(501) + "\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"OUT_OF_STOCK\",\"reasonText\":\"bell\\u0007\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"OUT_OF_STOCK\",\"reasonText\":\"line\\nbreak\"}",
				"{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":",
				"[]",
				"\"OUT_OF_STOCK\"",
				"",
				"not json");
		for (String json : bad) {
			send(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), json).andExpect(status().isBadRequest());
		}
		// No body at all, and a path that is not a UUID.
		mvc.perform(patch(path(product)).header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(change(product, UUID.randomUUID(), body()).with(kitchen(restaurant)).with(csrf())
				.contentType("text/plain")).andExpect(status().is4xxClientError());
		mvc.perform(patch("/api/v1/kitchen/products/not-a-uuid/availability").header(KEY, UUID.randomUUID().toString())
				.contentType("application/json").content(body()).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(menu);
		assertEquals(0, rows(restaurant));
	}

	@Test
	void aMaximumLengthReasonIsAccepted() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				body("A" + "B".repeat(39), "ş".repeat(500))).andExpect(status().isOk());
		assertEquals(1, rows(restaurant));
	}

	// ---- security ------------------------------------------------------------------------------------------------

	@Test
	void onlyAnAuthenticatedKitchenUserWithCsrfMayChangeAvailability() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		menuConfirmsEverything();

		mvc.perform(change(product, UUID.randomUUID(), body()).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(change(product, UUID.randomUUID(), body()).header("X-Role", "KITCHEN")
				.header("X-Restaurant-Id", restaurant.toString()).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(change(product, UUID.randomUUID(), body()).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(change(product, UUID.randomUUID(), body()).with(kitchen(restaurant))).andExpect(status().isForbidden());
		mvc.perform(get(path(product)).with(kitchen(restaurant))).andExpect(status().isMethodNotAllowed());
		mvc.perform(get(path(product)).with(admin(restaurant))).andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(menu);
		assertEquals(0, rows(restaurant));
	}

	@Test
	void aProductOfAnotherRestaurantIsNotFoundBecauseMenuDecidesByTheTenantOfThePrincipal() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID intruder = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		doAnswer(call -> {
			MenuAvailabilityCommand c = call.getArgument(0);
			return c.restaurantId().equals(owner) ? Result.applied(stateOf(c)) : Result.rejected(Kind.PRODUCT_NOT_FOUND, "PRODUCT_NOT_FOUND");
		}).when(menu).change(any());
		UUID key = UUID.randomUUID();

		send(intruder, UUID.randomUUID(), product, key, body()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND")).andExpect(jsonPath("$.retryable").value(false));
		send(owner, UUID.randomUUID(), product, UUID.randomUUID(), body()).andExpect(status().isOk());

		Map<String, Object> commandRow = row(intruder, key);
		assertEquals("REJECTED", commandRow.get("outcome"));
		assertEquals("PRODUCT_NOT_FOUND", commandRow.get("outcome_code"));
		assertNull(commandRow.get("confirmed_at"));
	}

	// ---- Idempotency-Key -----------------------------------------------------------------------------------------

	@Test
	void theIdempotencyKeyIsMandatoryUniqueAndACanonicalUuid() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		menuConfirmsEverything();

		mvc.perform(patch(path(product)).contentType("application/json").content(body()).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(patch(path(product)).contentType("application/json").content(body()).header(KEY, UUID.randomUUID().toString())
				.header(KEY, UUID.randomUUID().toString()).with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		for (String bad : List.of("x".repeat(5_000), "not-a-uuid", "{}", "null", "1-1-1-1-1",
				"0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22,0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a23", " 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22x")) {
			mvc.perform(patch(path(product)).contentType("application/json").content(body()).header(KEY, bad)
					.with(kitchen(restaurant)).with(csrf())).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(menu);
		assertEquals(0, rows(restaurant));
	}

	@Test
	void repeatingTheSameCommandIsAskedFromMenuAgainAndKeepsOneRecord() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, body("OUT_OF_STOCK", "bitti")).andExpect(status().isOk());
		// The uppercase spelling of the key is the same command.
		mvc.perform(patch(path(product)).header(KEY, key.toString().toUpperCase()).contentType("application/json")
				.content(body("OUT_OF_STOCK", "bitti")).with(kitchen(restaurant, actor)).with(csrf())).andExpect(status().isOk());

		verify(menu, times(2)).change(any());
		assertEquals(1, rows(restaurant));
		assertEquals(2, row(restaurant, key).get("attempt_count"));
		assertEquals("CONFIRMED", row(restaurant, key).get("outcome"));
	}

	@Test
	void theSameKeyForAnotherProductReasonTextOrUserIsAConflictThatNeverReachesMenu() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();
		send(restaurant, actor, product, key, body("OUT_OF_STOCK", "bitti")).andExpect(status().isOk());

		send(restaurant, actor, UUID.randomUUID(), key, body("OUT_OF_STOCK", "bitti")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT")).andExpect(jsonPath("$.retryable").value(false));
		send(restaurant, actor, product, key, body("OTHER_REASON", "bitti")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		send(restaurant, actor, product, key, body("OUT_OF_STOCK", "başka")).andExpect(status().isConflict());
		send(restaurant, actor, product, key, body("OUT_OF_STOCK", null)).andExpect(status().isConflict());
		send(restaurant, UUID.randomUUID(), product, key, body("OUT_OF_STOCK", "bitti")).andExpect(status().isConflict());

		verify(menu, times(1)).change(any());
		Map<String, Object> commandRow = row(restaurant, key);
		assertEquals(1, commandRow.get("attempt_count"));
		assertEquals("CONFIRMED", commandRow.get("outcome"));
		assertEquals(product, commandRow.get("product_id"));
		assertEquals(1, rows(restaurant));
	}

	@Test
	void theSameKeyInAnotherRestaurantIsAnIndependentCommand() throws Exception {
		UUID restaurantA = UUID.randomUUID();
		UUID restaurantB = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurantA, UUID.randomUUID(), product, key, body()).andExpect(status().isOk());
		send(restaurantB, UUID.randomUUID(), product, key, body()).andExpect(status().isOk());

		assertEquals(1, rows(restaurantA));
		assertEquals(1, rows(restaurantB));
	}

	// ---- Menu answers ----------------------------------------------------------------------------------------------

	@Test
	void aDefiniteRejectionByMenuIsMappedAndRecordedAsRejected() throws Exception {
		record Case(Kind kind, int status, String code, boolean retryable) {
		}
		for (Case c : List.of(
				new Case(Kind.PRODUCT_NOT_FOUND, 404, "PRODUCT_NOT_FOUND", false),
				new Case(Kind.INVALID_COMMAND, 400, "INVALID_COMMAND", false),
				new Case(Kind.COMMAND_CONFLICT, 409, "COMMAND_ID_CONFLICT", false),
				new Case(Kind.VERSION_CONFLICT, 409, "AVAILABILITY_CONFLICT", true))) {
			UUID restaurant = UUID.randomUUID();
			UUID key = UUID.randomUUID();
			menuAnswers(Result.rejected(c.kind(), "MENU_" + c.kind()));

			send(restaurant, UUID.randomUUID(), UUID.randomUUID(), key, body()).andExpect(status().is(c.status()))
					.andExpect(jsonPath("$.code").value(c.code())).andExpect(jsonPath("$.retryable").value(c.retryable()))
					.andExpect(header().doesNotExist("Retry-After"));

			Map<String, Object> commandRow = row(restaurant, key);
			assertEquals("REJECTED", commandRow.get("outcome"), c.kind().name());
			assertEquals(c.kind().name(), commandRow.get("outcome_code"));
			assertNull(commandRow.get("confirmed_at"));
		}
	}

	@Test
	void anUnreachableMenuIsAnUnknownOutcomeNeverASuccessAndTheSameKeyCanRetry() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuAnswers(Result.unavailable("TIMEOUT"));

		send(restaurant, actor, product, key, body()).andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "2")).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true)).andExpect(jsonPath("$.status").value(503));
		Map<String, Object> first = row(restaurant, key);
		assertEquals("UNCONFIRMED", first.get("outcome"));
		assertEquals("TIMEOUT", first.get("outcome_code"));
		assertNull(first.get("confirmed_at"));
		assertEquals(1, first.get("attempt_count"));

		menuConfirmsEverything();
		send(restaurant, actor, product, key, body()).andExpect(status().isOk());
		Map<String, Object> second = row(restaurant, key);
		assertEquals("CONFIRMED", second.get("outcome"));
		assertNull(second.get("outcome_code"));
		assertEquals(2, second.get("attempt_count"));
		assertEquals(1, rows(restaurant));
	}

	@Test
	void anExceptionOrNoAnswerFromThePortIsAnUnknownOutcomeAndNoMessageLeaks() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID thrown = UUID.randomUUID();
		doThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/menu password=hunter2")).when(menu).change(any());

		String response = send(restaurant, UUID.randomUUID(), UUID.randomUUID(), thrown, body())
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"))
				.andReturn().getResponse().getContentAsString();
		assertFalse(response.contains("hunter2") || response.contains("secret-host"), response);
		assertEquals("PORT_EXCEPTION", row(restaurant, thrown).get("outcome_code"));
		assertEquals("UNCONFIRMED", row(restaurant, thrown).get("outcome"));

		UUID silent = UUID.randomUUID();
		doReturn(null).when(menu).change(any());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), silent, body()).andExpect(status().isServiceUnavailable());
		assertEquals("NO_RESULT", row(restaurant, silent).get("outcome_code"));
		assertEquals("UNCONFIRMED", row(restaurant, silent).get("outcome"));
	}

	@Test
	void aMenuErrorCodeIsStoredOnlyAsAShortHarmlessToken() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuAnswers(Result.unavailable("http://10.0.0.5/x?token=abc <script>" + "z".repeat(200)));

		String response = send(restaurant, UUID.randomUUID(), UUID.randomUUID(), key, body()).andExpect(status().isServiceUnavailable())
				.andReturn().getResponse().getContentAsString();

		String stored = (String) row(restaurant, key).get("outcome_code");
		assertTrue(stored.length() <= 80, stored);
		assertTrue(stored.matches("[A-Za-z0-9_.-]+"), stored);
		assertFalse(response.contains("10.0.0.5") || response.contains("token=abc"), response);

		UUID blank = UUID.randomUUID();
		menuAnswers(Result.unavailable("  "));
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), blank, body()).andExpect(status().isServiceUnavailable());
		assertEquals("UNKNOWN", row(restaurant, blank).get("outcome_code"));
	}

	@Test
	void anInconsistentConfirmationIsNeverReportedAsSuccess() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		record Case(String name, Function<MenuAvailabilityCommand, Result> answer, int status, String code) {
		}
		for (Case c : List.of(
				new Case("no state", cmd -> new Result(Kind.APPLIED, null, null), 503, "INCONSISTENT_RESPONSE"),
				new Case("other product", cmd -> Result.applied(new ProductAvailabilityState(UUID.randomUUID(), cmd.targetStatus(),
						cmd.reasonCode(), cmd.reasonText(), Instant.parse(MENU_TIME), cmd.requestedByUserId(), 1L, true)), 503, "INCONSISTENT_RESPONSE"),
				new Case("no change time", cmd -> Result.applied(new ProductAvailabilityState(cmd.productId(), cmd.targetStatus(),
						cmd.reasonCode(), cmd.reasonText(), null, cmd.requestedByUserId(), 1L, true)), 503, "INCONSISTENT_RESPONSE"),
				new Case("other status", cmd -> Result.applied(new ProductAvailabilityState(cmd.productId(), ProductAvailabilityStatus.AVAILABLE,
						null, null, Instant.parse(MENU_TIME), UUID.randomUUID(), 5L, true)), 409, "AVAILABILITY_CONFLICT"))) {
			UUID key = UUID.randomUUID();
			doAnswer(call -> c.answer().apply(call.getArgument(0))).when(menu).change(any());

			send(restaurant, UUID.randomUUID(), product, key, body()).andExpect(status().is(c.status()))
					.andExpect(jsonPath("$.code").value(c.status() == 409 ? "AVAILABILITY_CONFLICT" : "MENU_UNAVAILABLE"))
					.andExpect(jsonPath("$.retryable").value(true));

			Map<String, Object> commandRow = row(restaurant, key);
			assertEquals("UNCONFIRMED", commandRow.get("outcome"), c.name());
			assertNull(commandRow.get("confirmed_at"), c.name());
			assertEquals(c.status() == 409 ? "STATE_MISMATCH" : "INCONSISTENT_RESPONSE", commandRow.get("outcome_code"), c.name());
		}
	}

	@Test
	void aRejectionIsFinalAndAConfirmationIsFinalWhateverMenuSaysOnARepeat() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();

		UUID rejected = UUID.randomUUID();
		menuAnswers(Result.rejected(Kind.PRODUCT_NOT_FOUND, "PRODUCT_NOT_FOUND"));
		send(restaurant, actor, product, rejected, body()).andExpect(status().isNotFound());
		menuConfirmsEverything();
		send(restaurant, actor, product, rejected, body()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
		assertEquals("REJECTED", row(restaurant, rejected).get("outcome"));
		assertNull(row(restaurant, rejected).get("confirmed_at"));

		UUID confirmed = UUID.randomUUID();
		send(restaurant, actor, product, confirmed, body()).andExpect(status().isOk());
		menuAnswers(Result.unavailable("TIMEOUT"));
		send(restaurant, actor, product, confirmed, body()).andExpect(status().isServiceUnavailable());
		menuAnswers(Result.rejected(Kind.INVALID_COMMAND, "INVALID_COMMAND"));
		send(restaurant, actor, product, confirmed, body()).andExpect(status().isBadRequest());
		assertEquals("CONFIRMED", row(restaurant, confirmed).get("outcome"));
		assertNotNull(row(restaurant, confirmed).get("confirmed_at"));
	}

	// ---- transactions and concurrency ------------------------------------------------------------------------------

	@Test
	void menuIsCalledWithNoTransactionOpenAndTheCommandAlreadyCommitted() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		AtomicBoolean transactionOpen = new AtomicBoolean(true);
		List<Map<String, Object>> seenByAnotherConnection = new ArrayList<>();
		doAnswer(call -> {
			transactionOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
			// Read through a different connection: only a COMMITTED, lock-free record is visible here.
			seenByAnotherConnection.add(jdbc.queryForMap(
					"SELECT outcome, attempt_count FROM product_availability_commands WHERE restaurant_id = ? AND command_id = ?",
					restaurant, key));
			return Result.applied(stateOf(call.getArgument(0)));
		}).when(menu).change(any());

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), key, body()).andExpect(status().isOk());

		assertFalse(transactionOpen.get(), "no transaction (and so no lock) may be open while Menu is called");
		assertEquals(1, seenByAnotherConnection.size());
		assertEquals("UNCONFIRMED", seenByAnotherConnection.get(0).get("outcome"));
		assertEquals(1, seenByAnotherConnection.get(0).get("attempt_count"));
	}

	@Test
	void parallelRequestsWithTheSameKeyAndIntentAllSucceedOverOneRecord() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		CountDownLatch concurrentMenuCalls = new CountDownLatch(8);
		doAnswer(call -> {
			concurrentMenuCalls.countDown();
			assertTrue(concurrentMenuCalls.await(10, TimeUnit.SECONDS), "all parallel requests should reach the Menu port");
			return Result.applied(stateOf(call.getArgument(0)));
		}).when(menu).change(any());

		for (int round = 0; round < 3; round++) {
			UUID key = UUID.randomUUID();
			List<Integer> statuses = fire(8, n -> change(product, key, body("OUT_OF_STOCK", "bitti")).with(kitchen(restaurant, actor)).with(csrf()));

			assertEquals(Collections.nCopies(8, 200), statuses);
			Map<String, Object> commandRow = row(restaurant, key);
			assertEquals("CONFIRMED", commandRow.get("outcome"));
			assertEquals(8, commandRow.get("attempt_count"), "every request was an attempt on the one record");
		}
		assertEquals(3, rows(restaurant));
	}

	@Test
	void parallelRequestsWithTheSameKeyButDifferentIntentLetExactlyOneWin() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		List<Integer> statuses = fire(2, n -> change(product, key, body(n == 0 ? "REASON_ONE" : "REASON_TWO", null))
				.with(kitchen(restaurant, actor)).with(csrf()));

		assertEquals(List.of(200, 409), statuses);
		verify(menu, times(1)).change(any());
		assertEquals(1, rows(restaurant));
	}

	@Test
	void changingAvailabilityLeavesKitchenOrdersAndTheirEventsUntouched() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		String snapshot = """
				SELECT o.status, o.started_at, o.ready_at, o.version,
				       (SELECT count(*) FROM kitchen_order_items i WHERE i.kitchen_order_id = o.id) AS items,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e WHERE e.restaurant_id = o.restaurant_id) AS events
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""";
		Map<String, Object> before = jdbc.queryForMap(snapshot, restaurant, orderId);
		menuConfirmsEverything();
		UUID product = jdbc.queryForObject("SELECT product_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id "
				+ "WHERE o.restaurant_id = ? LIMIT 1", UUID.class, restaurant);

		send(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), body()).andExpect(status().isOk());

		assertEquals(before, jdbc.queryForMap(snapshot, restaurant, orderId), "orders already in the kitchen keep their own snapshot");
	}

	// ---- KITCHEN-03 / 3.2: TEMPORARILY_CLOSED --------------------------------------------------------------------

	@Test
	void aTemporaryClosureIsReportedAsMenuConfirmedItWithTheWrittenReason() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu, yarın tamir edilecek"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TEMPORARILY_CLOSED"))
				.andExpect(jsonPath("$.reasonCode").value("EQUIPMENT_FAILURE"))
				.andExpect(jsonPath("$.reasonText").value("Fritöz bozuldu, yarın tamir edilecek"))
				.andExpect(jsonPath("$.changedAt").value(MENU_TIME)).andExpect(jsonPath("$.changedByUserId").value(actor.toString()));

		ArgumentCaptor<MenuAvailabilityCommand> sent = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
		verify(menu).change(sent.capture());
		assertEquals(new MenuAvailabilityCommand(key, restaurant, product, ProductAvailabilityStatus.TEMPORARILY_CLOSED,
				"EQUIPMENT_FAILURE", "Fritöz bozuldu, yarın tamir edilecek", actor), sent.getValue());
		Map<String, Object> commandRow = row(restaurant, key);
		assertEquals("TEMPORARILY_CLOSED", commandRow.get("target_status"));
		assertEquals("CONFIRMED", commandRow.get("outcome"));
		assertNotNull(commandRow.get("confirmed_at"));
	}

	@Test
	void aMalformedReasonOfAClosureIsStillRejectedBeforeAnythingIsSentOrRecorded() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		for (String json : List.of(
				closeBody("equipment failure", "Fritöz bozuldu"),
				closeBody("A", "Fritöz bozuldu"),
				closeBody("EQUIPMENT_FAILURE", "x".repeat(501)),
				closeBody("EQUIPMENT_FAILURE", "bell\\u0007"),
				closeBody("EQUIPMENT_FAILURE", "line\\nbreak"))) {
			send(restaurant, UUID.randomUUID(), product, UUID.randomUUID(), json).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(menu);
		assertEquals(0, rows(restaurant));
	}

	@Test
	void noReasonAtAllIsValidForEveryStatusAndNothingIsInventedInItsPlace() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		menuConfirmsEverything();
		int expectedCalls = 0;

		for (String status : List.of("OUT_OF_STOCK", "TEMPORARILY_CLOSED")) {
			for (String json : List.of(
					"{\"status\":\"" + status + "\"}",
					"{\"status\":\"" + status + "\",\"reasonCode\":null,\"reasonText\":null}",
					"{\"status\":\"" + status + "\",\"reasonCode\":\"\",\"reasonText\":\"   \"}")) {
				UUID product = UUID.randomUUID();
				UUID key = UUID.randomUUID();

				send(restaurant, actor, product, key, json).andExpect(status().isOk())
						.andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.reasonCode").value((Object) null))
						.andExpect(jsonPath("$.reasonText").value((Object) null));

				expectedCalls++;
				ArgumentCaptor<MenuAvailabilityCommand> sent = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
				verify(menu, times(expectedCalls)).change(sent.capture());
				MenuAvailabilityCommand command = sent.getValue();
				assertNull(command.reasonCode(), json);
				assertNull(command.reasonText(), json);
				Map<String, Object> commandRow = row(restaurant, key);
				assertNull(commandRow.get("reason_code"), json);
				assertNull(commandRow.get("reason_text"), json);
				assertEquals("CONFIRMED", commandRow.get("outcome"));
			}
		}
	}

	@Test
	void eitherReasonFieldAloneIsPassedOnAsGivenAndTheOtherStaysEmpty() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuConfirmsEverything();
		UUID codeOnly = UUID.randomUUID();
		UUID textOnly = UUID.randomUUID();

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), codeOnly,
				"{\"status\":\"TEMPORARILY_CLOSED\",\"reasonCode\":\"EQUIPMENT_FAILURE\"}")
				.andExpect(status().isOk());
		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), textOnly, "{\"status\":\"TEMPORARILY_CLOSED\",\"reasonText\":\"Fritöz bozuldu\"}")
				.andExpect(status().isOk());

		assertEquals("EQUIPMENT_FAILURE", row(restaurant, codeOnly).get("reason_code"));
		assertNull(row(restaurant, codeOnly).get("reason_text"));
		assertNull(row(restaurant, textOnly).get("reason_code"));
		assertEquals("Fritöz bozuldu", row(restaurant, textOnly).get("reason_text"));
	}

	@Test
	void withAndWithoutAReasonAreDifferentCommandsForTheSameKey() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, "{\"status\":\"OUT_OF_STOCK\"}").andExpect(status().isOk());
		// Spelled differently but still "no reason": the same command.
		send(restaurant, actor, product, key, "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"\",\"reasonText\":null}").andExpect(status().isOk());
		// Now a reason is given: another command.
		send(restaurant, actor, product, key, body("OUT_OF_STOCK", null)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		verify(menu, times(2)).change(any());
		assertEquals(1, rows(restaurant));
	}

	@Test
	void aTemporaryClosureIsForKitchenOnlyAndATimeSentByTheClientIsIgnored() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		menuConfirmsEverything();
		String json = closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu").replace("}",
				",\"changedAt\":\"1999-01-01T00:00:00Z\",\"closedUntil\":\"2999-01-01T00:00:00Z\"}");

		mvc.perform(change(product, UUID.randomUUID(), json).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(change(product, UUID.randomUUID(), json).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(change(product, UUID.randomUUID(), json).with(kitchen(restaurant))).andExpect(status().isForbidden());
		verifyNoInteractions(menu);

		send(restaurant, actor, product, UUID.randomUUID(), json).andExpect(status().isOk())
				.andExpect(jsonPath("$.changedAt").value(MENU_TIME));
		assertEquals(1, rows(restaurant));
	}

	@Test
	void aClosureRepeatIsAskedFromMenuAgainAndTheSameKeyForAnotherStatusIsAConflict() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, key, closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu")).andExpect(status().isOk());
		send(restaurant, actor, product, key, closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu")).andExpect(status().isOk());
		verify(menu, times(2)).change(any());
		assertEquals(2, row(restaurant, key).get("attempt_count"));

		// The same key now for "out of stock" or with another text is a different command: Menu is not asked.
		send(restaurant, actor, product, key, body("EQUIPMENT_FAILURE", "Fritöz bozuldu")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		send(restaurant, actor, product, key, closeBody("EQUIPMENT_FAILURE", "Başka gerekçe")).andExpect(status().isConflict());
		verify(menu, times(2)).change(any());
		assertEquals(1, rows(restaurant));
		assertEquals("CONFIRMED", row(restaurant, key).get("outcome"));
	}

	@Test
	void twoDifferentCommandsOnTheSameProductAtOnceLetMenuDecideAndNeitherRecordOverwritesTheOther() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		AtomicBoolean menuTaken = new AtomicBoolean(false);
		CountDownLatch concurrentMenuCalls = new CountDownLatch(2);
		// Menu stand-in: the first command to arrive wins, the other one lost against a newer state.
		doAnswer(call -> {
			concurrentMenuCalls.countDown();
			assertTrue(concurrentMenuCalls.await(10, TimeUnit.SECONDS), "both competing commands should reach the Menu port");
			return menuTaken.compareAndSet(false, true) ? Result.applied(stateOf(call.getArgument(0)))
					: Result.rejected(Kind.VERSION_CONFLICT, "VERSION_CONFLICT");
		}).when(menu).change(any());
		UUID closeKey = UUID.randomUUID();
		UUID stockKey = UUID.randomUUID();

		List<Integer> statuses = fire(2, n -> n == 0
				? change(product, closeKey, closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu")).with(kitchen(restaurant)).with(csrf())
				: change(product, stockKey, body()).with(kitchen(restaurant)).with(csrf()));

		assertEquals(List.of(200, 409), statuses);
		assertEquals(2, rows(restaurant));
		long confirmed = jdbc.queryForObject(
				"SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ? AND outcome = 'CONFIRMED'", Long.class, restaurant);
		long rejected = jdbc.queryForObject("SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ? "
				+ "AND outcome = 'REJECTED' AND outcome_code = 'VERSION_CONFLICT'", Long.class, restaurant);
		assertEquals(1, confirmed);
		assertEquals(1, rejected);
	}

	@Test
	void theLosingCommandOfARaceIsRetryableAndAnswersWithTheAvailabilityConflict() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuAnswers(Result.rejected(Kind.VERSION_CONFLICT, "VERSION_CONFLICT"));

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_CONFLICT"))
				.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void everyMenuAnswerToAClosureIsMappedAndNeverShownAsClosedUnlessMenuConfirmed() throws Exception {
		record Case(Function<MenuAvailabilityCommand, Result> answer, int status, String code, String outcome) {
		}
		// Menu "confirms" but reports only OUT_OF_STOCK (someone else changed the product meanwhile): not a closure.
		Function<MenuAvailabilityCommand, Result> otherState = cmd -> Result.applied(new ProductAvailabilityState(cmd.productId(),
				ProductAvailabilityStatus.OUT_OF_STOCK, "OUT_OF_STOCK", null, Instant.parse(MENU_TIME), UUID.randomUUID(), 4L, true));
		for (Case c : List.of(
				new Case(cmd -> Result.rejected(Kind.PRODUCT_NOT_FOUND, "X"), 404, "PRODUCT_NOT_FOUND", "REJECTED"),
				new Case(cmd -> Result.rejected(Kind.INVALID_COMMAND, "X"), 400, "INVALID_COMMAND", "REJECTED"),
				new Case(cmd -> Result.rejected(Kind.COMMAND_CONFLICT, "X"), 409, "COMMAND_ID_CONFLICT", "REJECTED"),
				new Case(cmd -> Result.unavailable("HTTP_503"), 503, "MENU_UNAVAILABLE", "UNCONFIRMED"),
				new Case(cmd -> Result.unavailable("TIMEOUT"), 503, "MENU_UNAVAILABLE", "UNCONFIRMED"),
				new Case(otherState, 409, "AVAILABILITY_CONFLICT", "UNCONFIRMED"))) {
			UUID restaurant = UUID.randomUUID();
			UUID key = UUID.randomUUID();
			doAnswer(call -> c.answer().apply(call.getArgument(0))).when(menu).change(any());

			send(restaurant, UUID.randomUUID(), UUID.randomUUID(), key, closeBody("EQUIPMENT_FAILURE", "Fritöz bozuldu"))
					.andExpect(status().is(c.status())).andExpect(jsonPath("$.code").value(c.code()));

			Map<String, Object> commandRow = row(restaurant, key);
			assertEquals(c.outcome(), commandRow.get("outcome"), c.code());
			assertNull(commandRow.get("confirmed_at"), c.code());
		}
	}

	// ---- KITCHEN-03 / 3.3: reopening (AVAILABLE) -------------------------------------------------------------------

	@Test
	void reopeningIsGuardedLikeClosingAndNeverReachesMenuWithoutKitchenRoleAndCsrf() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		menuConfirmsEverything();
		String reopen = "{\"status\":\"AVAILABLE\"}";

		mvc.perform(change(product, UUID.randomUUID(), reopen).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(change(product, UUID.randomUUID(), reopen).with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(change(product, UUID.randomUUID(), reopen).with(kitchen(restaurant))).andExpect(status().isForbidden());
		mvc.perform(patch(path(product)).contentType("application/json").content(reopen).with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(menu);
		assertEquals(0, rows(restaurant));
	}

	@Test
	void aReopenIsReportedAsOpenOnlyWithMenusConfirmationAndAReasonIsOptional() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID plain = UUID.randomUUID();
		UUID explained = UUID.randomUUID();
		menuConfirmsEverything();

		send(restaurant, actor, product, plain, "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.reasonCode").value((Object) null))
				.andExpect(jsonPath("$.changedAt").value(MENU_TIME)).andExpect(jsonPath("$.published").value(true));
		send(restaurant, actor, product, explained, "{\"status\":\"AVAILABLE\",\"reasonCode\":\"RESTOCKED\",\"reasonText\":\"Geldi\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.reasonText").value("Geldi"));

		ArgumentCaptor<MenuAvailabilityCommand> sent = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
		verify(menu, times(2)).change(sent.capture());
		assertEquals(ProductAvailabilityStatus.AVAILABLE, sent.getAllValues().get(0).targetStatus());
		assertNull(sent.getAllValues().get(0).reasonCode());
		assertEquals("RESTOCKED", sent.getAllValues().get(1).reasonCode());
		assertEquals("CONFIRMED", row(restaurant, plain).get("outcome"));
	}

	@Test
	void aReopenAnsweredWithAnotherStateOrNoAnswerIsNeverShownAsOpen() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID stale = UUID.randomUUID();
		// Menu says the product is closed again (somebody closed it after the user looked): not "open".
		doAnswer(call -> Result.applied(new ProductAvailabilityState(product, ProductAvailabilityStatus.TEMPORARILY_CLOSED, "BUSY",
				"Yoğunluk", Instant.parse(MENU_TIME), UUID.randomUUID(), 9L, true))).when(menu).change(any());

		send(restaurant, UUID.randomUUID(), product, stale, "{\"status\":\"AVAILABLE\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AVAILABILITY_CONFLICT")).andExpect(jsonPath("$.retryable").value(true));
		assertEquals("UNCONFIRMED", row(restaurant, stale).get("outcome"));
		assertNull(row(restaurant, stale).get("confirmed_at"));

		UUID silent = UUID.randomUUID();
		menuAnswers(Result.unavailable("TIMEOUT"));
		send(restaurant, UUID.randomUUID(), product, silent, "{\"status\":\"AVAILABLE\"}").andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "2")).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"));
		assertEquals("UNCONFIRMED", row(restaurant, silent).get("outcome"));
	}

	@Test
	void aPassiveProductAnswerIsAConflictThatIsFinalAndNotRetryable() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		menuAnswers(Result.rejected(Kind.PRODUCT_INACTIVE, "PRODUCT_INACTIVE"));

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), key, "{\"status\":\"AVAILABLE\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE")).andExpect(jsonPath("$.retryable").value(false));
		assertEquals("REJECTED", row(restaurant, key).get("outcome"));
		assertEquals("PRODUCT_INACTIVE", row(restaurant, key).get("outcome_code"));
	}

	@Test
	void everyFailureAnswerIsProblemJsonWithoutAStackTrace() throws Exception {
		UUID restaurant = UUID.randomUUID();
		menuAnswers(Result.unavailable("TIMEOUT"));

		send(restaurant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), body()).andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.trace").doesNotExist()).andExpect(jsonPath("$.exception").doesNotExist());
	}
}
