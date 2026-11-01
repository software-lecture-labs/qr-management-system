package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Optional query parameters: absent uses the default, present-but-empty/blank/invalid is always 400. */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenQueueParameterValidationTests {

	private static final String URL = "/api/v1/kitchen/orders";
	private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void absentParametersKeepTheDefaults() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID waiting = new KitchenOrderSeeder(jdbc).seed(restaurant, "WAITING", T0);
		new KitchenOrderSeeder(jdbc).seed(restaurant, "READY", T0);

		mvc.perform(get(URL).with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(waiting.toString()))
				.andExpect(jsonPath("$.content[0].status").value("WAITING"));
	}

	@Test
	void eachEmptyOrBlankParameterIs400AndNeverFallsBackToADefault() throws Exception {
		UUID restaurant = UUID.randomUUID();
		new KitchenOrderSeeder(jdbc).seed(restaurant, "WAITING", T0);

		for (String name : new String[] { "status", "receivedFrom", "receivedTo", "page", "size" }) {
			for (String value : new String[] { "", "%20", "%20%20", "%09" }) {
				assertBadRequest(restaurant, "?" + name + "=" + value);
			}
			// Also when the empty parameter is combined with otherwise valid ones, in either position.
			assertBadRequest(restaurant,
					"?" + name + "=&status=ACTIVE&page=0&size=5".replace("&status=ACTIVE", name.equals("status") ? "" : "&status=ACTIVE"));
			assertBadRequest(restaurant, "?page=0&size=5&" + name + "=");
		}
		assertBadRequest(restaurant, "?receivedFrom=&receivedTo=&page=&size=");
	}

	@Test
	void invalidDatesAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String value : new String[] { "yesterday", "2026-13-45T00:00:00Z", "2026-02-30T00:00:00Z", "2026-10-17",
				"2026-10-17T10:00:00", "10:00", "0", "null", "2026-10-17T10:00:00Z,2026-10-18T10:00:00Z" }) {
			assertBadRequest(restaurant, "?receivedFrom=" + value);
			assertBadRequest(restaurant, "?receivedTo=" + value);
		}
		mvc.perform(get(URL + "?receivedFrom=2026-10-17T10:00:00.123Z&receivedTo=2026-10-17T11:00:00Z").with(kitchen(restaurant)))
				.andExpect(status().isOk());
	}

	@Test
	void invalidDateRangesAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();
		String t = T0.toString();
		assertBadRequest(restaurant, "?receivedFrom=" + t + "&receivedTo=" + t);
		assertBadRequest(restaurant, "?receivedFrom=" + T0.plusSeconds(1) + "&receivedTo=" + t);
		// A single bound can never form an invalid range.
		mvc.perform(get(URL + "?receivedFrom=" + t).with(kitchen(restaurant))).andExpect(status().isOk());
		mvc.perform(get(URL + "?receivedTo=" + t).with(kitchen(restaurant))).andExpect(status().isOk());
	}

	@Test
	void invalidPageNumbersAreRejected() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String value : new String[] { "-1", "-100", "abc", "1.5", "1e2", "99999999999", "2147483648", "0x10",
				"1,2", "--1" }) {
			assertBadRequest(restaurant, "?page=" + value);
		}
		assertBadRequest(restaurant, "?page=1&page=2");
		mvc.perform(get(URL + "?page=0").with(kitchen(restaurant))).andExpect(status().isOk());
		mvc.perform(get(URL + "?page=7").with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(7))
				.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void invalidPageSizesAreRejectedAndTheBoundsAreAccepted() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String value : new String[] { "0", "-1", "-5", "101", "1000", "abc", "1.5", "2147483648", "10,20" }) {
			assertBadRequest(restaurant, "?size=" + value);
		}
		mvc.perform(get(URL + "?size=1").with(kitchen(restaurant)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
		mvc.perform(get(URL + "?size=100").with(kitchen(restaurant)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
	}

	@Test
	void validationNeverRunsBeforeAuthentication() throws Exception {
		mvc.perform(get(URL + "?page=&size=0&receivedFrom=x")).andExpect(status().isUnauthorized());
		mvc.perform(get(URL + "?page=&size=0&receivedFrom=x").with(user("waiter").roles("WAITER")))
				.andExpect(status().isForbidden());
	}

	private void assertBadRequest(UUID restaurant, String query) throws Exception {
		String body = mvc.perform(get(URL + query).with(kitchen(restaurant)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.content").doesNotExist())
				.andExpect(jsonPath("$.trace").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		// The offending value is never reflected back to the caller.
		assertFalse(body.contains("yesterday") || body.contains("2147483648") || body.contains("abc"), body);
	}
}
