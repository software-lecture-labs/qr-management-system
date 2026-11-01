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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitchenQueueFilterApiTests {

	private static final String URL = "/api/v1/kitchen/orders";
	private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	private KitchenOrderSeeder seeder() {
		return new KitchenOrderSeeder(jdbc);
	}

	@Test
	void everyStatusFilterReturnsOnlyItsStatusAndActiveMeansWaitingPlusPreparingOldestFirst() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID preparing = seeder().seed(restaurant, "PREPARING", T0);
		UUID waiting = seeder().seed(restaurant, "WAITING", T0.plusSeconds(10));
		UUID ready = seeder().seed(restaurant, "READY", T0.plusSeconds(20));
		UUID cancelled = seeder().seed(restaurant, "CANCELLED", T0.plusSeconds(30));

		mvc.perform(get(URL + "?status=WAITING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(waiting.toString()))
				.andExpect(jsonPath("$.content[0].status").value("WAITING"));
		mvc.perform(get(URL + "?status=PREPARING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(preparing.toString()));
		mvc.perform(get(URL + "?status=READY").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(ready.toString()));
		mvc.perform(get(URL + "?status=CANCELLED").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(cancelled.toString()));
		mvc.perform(get(URL + "?status=ACTIVE").with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].orderId").value(preparing.toString()))
				.andExpect(jsonPath("$.content[0].status").value("PREPARING"))
				.andExpect(jsonPath("$.content[1].orderId").value(waiting.toString()))
				.andExpect(jsonPath("$.content[1].status").value("WAITING"));
	}

	@Test
	void oldestFirstQueueIsKeptAndNewArrivalsAreOnlyMarkedNotReordered() throws Exception {
		UUID restaurant = UUID.randomUUID();
		// An older order already being prepared must stay ahead of a newer waiting one (FIFO, D-06).
		UUID oldPreparing = seeder().seed(restaurant, "PREPARING", T0);
		UUID oldWaiting = seeder().seed(restaurant, "WAITING", T0.plusSeconds(5));
		UUID newestWaiting = seeder().seed(restaurant, "WAITING", T0.plusSeconds(10));

		mvc.perform(get(URL + "?status=ACTIVE").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.content[0].orderId").value(oldPreparing.toString()))
				.andExpect(jsonPath("$.content[0].newArrival").value(false))
				.andExpect(jsonPath("$.content[1].orderId").value(oldWaiting.toString()))
				.andExpect(jsonPath("$.content[1].newArrival").value(true))
				.andExpect(jsonPath("$.content[2].orderId").value(newestWaiting.toString()))
				.andExpect(jsonPath("$.content[2].newArrival").value(true));
		mvc.perform(get(URL + "?status=PREPARING").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.content[0].newArrival").value(false));
	}

	@Test
	void emptyQueueReturnsAnEmptyPageForEveryStatus() throws Exception {
		UUID restaurant = UUID.randomUUID();
		for (String s : new String[] { "WAITING", "PREPARING", "READY", "CANCELLED", "ACTIVE" }) {
			mvc.perform(get(URL + "?status=" + s).with(kitchen(restaurant)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.content").isEmpty())
					.andExpect(jsonPath("$.totalElements").value(0))
					.andExpect(jsonPath("$.totalPages").value(0));
		}
	}

	@Test
	void otherRestaurantsRecordsNeverAppearForAnyStatus() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		for (String s : new String[] { "WAITING", "PREPARING", "READY", "CANCELLED" }) {
			seeder().seed(owner, s, T0);
		}
		for (String s : new String[] { "WAITING", "PREPARING", "READY", "CANCELLED", "ACTIVE" }) {
			mvc.perform(get(URL + "?status=" + s + "&receivedFrom=2000-01-01T00:00:00Z").with(kitchen(other)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElements").value(0));
		}
		mvc.perform(get(URL + "?status=ACTIVE").with(kitchen(owner)))
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void equalTimestampsAreOrderedDeterministicallyById() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID third = seeder().seed(restaurant, UUID.fromString("00000000-0000-0000-0000-000000000003"), "WAITING", T0);
		UUID first = seeder().seed(restaurant, UUID.fromString("00000000-0000-0000-0000-000000000001"), "WAITING", T0);
		UUID second = seeder().seed(restaurant, UUID.fromString("00000000-0000-0000-0000-000000000002"), "WAITING", T0);

		for (int run = 0; run < 2; run++) {
			mvc.perform(get(URL).with(kitchen(restaurant)))
					.andExpect(jsonPath("$.content[0].orderId").value(first.toString()))
					.andExpect(jsonPath("$.content[1].orderId").value(second.toString()))
					.andExpect(jsonPath("$.content[2].orderId").value(third.toString()));
		}
		// Page boundaries must not duplicate or skip rows when timestamps tie.
		mvc.perform(get(URL + "?size=2&page=1").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(third.toString()));
	}

	@Test
	void dateRangeIsInclusiveFromAndExclusiveTo() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID early = seeder().seed(restaurant, "WAITING", T0);
		UUID middle = seeder().seed(restaurant, "WAITING", T0.plusSeconds(60));
		UUID late = seeder().seed(restaurant, "WAITING", T0.plusSeconds(120));

		mvc.perform(get(URL + "?receivedFrom=" + T0.plusSeconds(60) + "&receivedTo=" + T0.plusSeconds(120))
						.with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(middle.toString()));
		mvc.perform(get(URL + "?receivedFrom=" + T0.plusSeconds(60)).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].orderId").value(middle.toString()))
				.andExpect(jsonPath("$.content[1].orderId").value(late.toString()));
		mvc.perform(get(URL + "?receivedTo=" + T0.plusSeconds(60)).with(kitchen(restaurant)))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(early.toString()));
	}

	@Test
	void invalidDateRangesAndStatusSpellingsReturn400() throws Exception {
		UUID restaurant = UUID.randomUUID();
		String same = T0.toString();
		for (String query : new String[] {
				"?receivedFrom=" + same + "&receivedTo=" + same,
				"?receivedFrom=" + T0.plusSeconds(1) + "&receivedTo=" + same,
				"?receivedFrom=yesterday",
				"?receivedTo=2026-13-45T00:00:00Z",
				"?status=active",
				"?status=DELIVERED" }) {
			mvc.perform(get(URL + query).with(kitchen(restaurant)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.status").value(400));
		}
	}

	@Test
	void absentStatusDefaultsToWaitingButAPresentEmptyOrBlankStatusIs400() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID waiting = seeder().seed(restaurant, "WAITING", T0);
		seeder().seed(restaurant, "READY", T0);

		mvc.perform(get(URL).with(kitchen(restaurant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].orderId").value(waiting.toString()));
		for (String query : new String[] { "?status=", "?status=%20", "?status=%20%20", "?status=&page=0" }) {
			mvc.perform(get(URL + query).with(kitchen(restaurant)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.status").value(400))
					.andExpect(jsonPath("$.detail").value("status must be one of WAITING, PREPARING, READY, CANCELLED, ACTIVE"));
		}
		// The 400 is a client error only; an empty status must never fall back to any list.
		mvc.perform(get(URL + "?status=").with(kitchen(restaurant)))
				.andExpect(jsonPath("$.content").doesNotExist());
	}

	@Test
	void unauthorizedCallersAreRejectedBeforeAnyQueryRuns() throws Exception {
		UUID restaurant = UUID.randomUUID();
		seeder().seed(restaurant, "WAITING", T0);
		mvc.perform(get(URL + "?status=ACTIVE")).andExpect(status().isUnauthorized());
		mvc.perform(get(URL + "?status=ACTIVE").with(user("waiter").roles("WAITER"))).andExpect(status().isForbidden());
	}
}
