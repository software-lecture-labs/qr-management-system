package com.restofy.kitchen.api.controller;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KITCHEN-03 / 3.4: GET /api/v1/kitchen/reports/daily. Rows are seeded directly with exact instants so the day
 * boundaries, the time zone, daylight saving and the counting rules can be checked to the millisecond.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenDailyReportApiTests {

	private static final String PATH = "/api/v1/kitchen/reports/daily";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	private static Timestamp ts(String instant) {
		return instant == null ? null : Timestamp.from(Instant.parse(instant));
	}

	private void order(UUID restaurant, String status, String received, String started, String ready, String cancelled) {
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, started_at, ready_at, cancelled_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', ?, ?, ?, ?, ?, 1, ?)
				""", UUID.randomUUID(), restaurant, UUID.randomUUID(), UUID.randomUUID(), status, ts(received), ts(started), ts(ready),
				ts(cancelled), UUID.randomUUID());
	}

	private void waste(UUID restaurant, int quantity, String reason, String recordedAt) {
		jdbc.update("""
				INSERT INTO waste_records (id, restaurant_id, command_id, product_id, quantity, reason_code, recorded_at, recorded_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), restaurant, UUID.randomUUID(), UUID.randomUUID(), quantity, reason, ts(recordedAt), UUID.randomUUID());
	}

	private ResultActions report(UUID restaurant, String query) throws Exception {
		return mvc.perform(get(PATH + query).with(kitchen(restaurant)));
	}

	/** Orders around the Istanbul day 2025-10-20 = [2025-10-19T21:00Z, 2025-10-20T21:00Z). */
	private UUID seedIstanbulDay() {
		UUID restaurant = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		order(restaurant, "WAITING", "2025-10-19T20:59:59Z", null, null, null);            // A: the minute before the day
		order(restaurant, "WAITING", "2025-10-19T21:00:00Z", null, null, null);            // B: first instant of the day
		order(restaurant, "PREPARING", "2025-10-20T10:00:00Z", "2025-10-20T10:01:00Z", null, null); // C
		order(restaurant, "READY", "2025-10-20T11:00:00Z", "2025-10-20T11:01:00Z", "2025-10-20T11:02:00Z", null); // D
		order(restaurant, "CANCELLED", "2025-10-20T12:00:00Z", null, null, "2025-10-20T12:00:30Z"); // E
		order(restaurant, "WAITING", "2025-10-20T20:59:59.999Z", null, null, null);        // F: last instant of the day
		order(restaurant, "WAITING", "2025-10-20T21:00:00Z", null, null, null);            // G: first instant of the next day
		order(restaurant, "WAITING", "2025-10-20T22:00:00Z", null, null, null);            // K: next day in Istanbul, same day in UTC
		order(restaurant, "READY", "2025-10-19T12:00:00Z", "2025-10-19T12:01:00Z", "2025-10-20T08:00:00Z",
				null); // H: received before, ready inside
		order(restaurant, "CANCELLED", "2025-10-19T12:00:00Z", null, null, "2025-10-20T09:00:00Z"); // I: received before, cancelled inside
		order(other, "READY", "2025-10-20T10:00:00Z", "2025-10-20T10:01:00Z", "2025-10-20T10:02:00Z", null); // another restaurant
		waste(restaurant, 3, "SPOILAGE", "2025-10-20T10:00:00Z");
		waste(restaurant, 2, "SPOILAGE", "2025-10-19T21:00:00Z");                          // first instant of the day
		waste(restaurant, 5, "OTHER", "2025-10-20T21:00:00Z");                             // first instant of the next day
		waste(restaurant, 1, "WRONG_PREPARATION", "2025-10-19T20:59:59Z");                 // the minute before the day
		waste(other, 40, "SPOILAGE", "2025-10-20T10:00:00Z");
		return restaurant;
	}

	@Test
	void theDayIsCountedInTheGivenZoneWithExactBoundariesAndEveryNumberMeansOneThing() throws Exception {
		UUID restaurant = seedIstanbulDay();

		report(restaurant, "?date=2025-10-20&timeZone=Europe/Istanbul").andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value("2025-10-20")).andExpect(jsonPath("$.timeZone").value("Europe/Istanbul"))
				.andExpect(jsonPath("$.windowStart").value("2025-10-19T21:00:00Z")).andExpect(jsonPath("$.windowEnd").value("2025-10-20T21:00:00Z"))
				.andExpect(jsonPath("$.dayComplete").value(true))
				// received: B, C, D, E, F
				.andExpect(jsonPath("$.orders.received").value(5))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.waiting").value(2))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.preparing").value(1))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.ready").value(1))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.cancelled").value(1))
				// transitions inside the day: D and H became ready, E and I were cancelled
				.andExpect(jsonPath("$.orders.duringDay.becameReady").value(2)).andExpect(jsonPath("$.orders.duringDay.cancelled").value(2))
				// waste inside the day: the two SPOILAGE entries only
				.andExpect(jsonPath("$.waste.totalQuantity").value(5)).andExpect(jsonPath("$.waste.recordCount").value(2))
				.andExpect(jsonPath("$.waste.byReason.length()").value(5))
				.andExpect(jsonPath("$.waste.byReason[0].reasonCode").value("WRONG_PREPARATION"))
				.andExpect(jsonPath("$.waste.byReason[0].quantity").value(0))
				.andExpect(jsonPath("$.waste.byReason[1].reasonCode").value("SPOILAGE"))
				.andExpect(jsonPath("$.waste.byReason[1].quantity").value(5)).andExpect(jsonPath("$.waste.byReason[1].records").value(2))
				.andExpect(jsonPath("$.waste.byReason[2].reasonCode").value("DROPPED_OR_DAMAGED"))
				.andExpect(jsonPath("$.waste.byReason[3].reasonCode").value("WASTE_AFTER_CANCELLATION"))
				.andExpect(jsonPath("$.waste.byReason[4].reasonCode").value("OTHER")).andExpect(jsonPath("$.waste.byReason[4].quantity").value(0))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
	}

	@Test
	void theSameDataGivesAnotherDayInAnotherZone() throws Exception {
		UUID restaurant = seedIstanbulDay();

		// UTC day = [2025-10-20T00:00Z, 2025-10-21T00:00Z): C, D, E, F, G and K were received, B was not.
		report(restaurant, "?date=2025-10-20&timeZone=UTC").andExpect(status().isOk())
				.andExpect(jsonPath("$.windowStart").value("2025-10-20T00:00:00Z")).andExpect(jsonPath("$.windowEnd").value("2025-10-21T00:00:00Z"))
				.andExpect(jsonPath("$.orders.received").value(6)).andExpect(jsonPath("$.orders.currentStatusOfReceived.waiting").value(3))
				.andExpect(jsonPath("$.waste.totalQuantity").value(8)).andExpect(jsonPath("$.waste.recordCount").value(2));
	}

	@Test
	void theStatusesOfTheReceivedOrdersAlwaysAddUpToReceivedAndAnEmptyDayIsAllZeros() throws Exception {
		UUID restaurant = seedIstanbulDay();

		report(restaurant, "?date=2025-10-20&timeZone=Europe/Istanbul").andExpect(jsonPath("$.orders.received")
				.value(Matchers.greaterThan(0)));
		report(UUID.randomUUID(), "?date=2025-10-20&timeZone=Europe/Istanbul").andExpect(status().isOk())
				.andExpect(jsonPath("$.orders.received").value(0)).andExpect(jsonPath("$.orders.currentStatusOfReceived.waiting").value(0))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.preparing").value(0))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.ready").value(0))
				.andExpect(jsonPath("$.orders.currentStatusOfReceived.cancelled").value(0))
				.andExpect(jsonPath("$.orders.duringDay.becameReady").value(0)).andExpect(jsonPath("$.orders.duringDay.cancelled").value(0))
				.andExpect(jsonPath("$.waste.totalQuantity").value(0)).andExpect(jsonPath("$.waste.recordCount").value(0))
				.andExpect(jsonPath("$.waste.byReason.length()").value(5));
	}

	@Test
	void aDayWithDaylightSavingIsTwentyThreeOrTwentyFiveHoursLong() throws Exception {
		UUID restaurant = UUID.randomUUID();
		// New York falls back on 2026-11-01 (25 hours) and springs forward on 2026-03-08 (23 hours).
		waste(restaurant, 4, "SPOILAGE", "2026-11-02T04:59:59Z");   // 23:59:59 EST on 1 November: still inside
		waste(restaurant, 9, "SPOILAGE", "2026-11-02T05:00:00Z");   // midnight EST on 2 November: next day
		waste(restaurant, 7, "OTHER", "2026-03-08T04:59:59Z");      // 23:59:59 EST on 7 March: the day before
		waste(restaurant, 1, "OTHER", "2026-03-08T05:00:00Z");      // midnight EST on 8 March: inside
		waste(restaurant, 6, "OTHER", "2026-03-09T03:59:59Z");      // 23:59:59 EDT on 8 March: inside

		report(restaurant, "?date=2026-11-01&timeZone=America/New_York").andExpect(status().isOk())
				.andExpect(jsonPath("$.windowStart").value("2026-11-01T04:00:00Z")).andExpect(jsonPath("$.windowEnd").value("2026-11-02T05:00:00Z"))
				.andExpect(jsonPath("$.waste.totalQuantity").value(4));
		report(restaurant, "?date=2026-03-08&timeZone=America/New_York").andExpect(status().isOk())
				.andExpect(jsonPath("$.windowStart").value("2026-03-08T05:00:00Z")).andExpect(jsonPath("$.windowEnd").value("2026-03-09T04:00:00Z"))
				.andExpect(jsonPath("$.waste.totalQuantity").value(7));
	}

	@Test
	void aDayThatHasNotEndedYetIsMarkedIncomplete() throws Exception {
		UUID restaurant = UUID.randomUUID();

		report(restaurant, "?date=2020-01-01&timeZone=Europe/Istanbul").andExpect(jsonPath("$.dayComplete").value(true));
		report(restaurant, "?date=2099-12-31&timeZone=Europe/Istanbul").andExpect(status().isOk())
				.andExpect(jsonPath("$.dayComplete").value(false));
	}

	@Test
	void aMissingEmptyOrUnknownDateOrZoneIsABadRequestAndNoZoneIsEverAssumed() throws Exception {
		UUID restaurant = seedIstanbulDay();
		for (String query : List.of("", "?date=2025-10-20", "?timeZone=Europe/Istanbul", "?date=&timeZone=Europe/Istanbul",
				"?date=2025-10-20&timeZone=", "?date=2025-10-20&timeZone=%20", "?date=20-10-2026&timeZone=Europe/Istanbul",
				"?date=2026-13-40&timeZone=Europe/Istanbul", "?date=2025-10-20T10:00:00Z&timeZone=Europe/Istanbul",
				"?date=1999-12-31&timeZone=Europe/Istanbul", "?date=2101-01-01&timeZone=Europe/Istanbul",
				"?date=%2B999999999-12-31&timeZone=Europe/Istanbul", "?date=2025-10-20&timeZone=%2B03:00",
				"?date=2025-10-20&timeZone=UTC%2B3", "?date=2025-10-20&timeZone=Mars/Base", "?date=2025-10-20&timeZone=europe/istanbul",
				"?date=2025-10-20&timeZone=Europe/Istanbul&timeZone=UTC")) {
			report(restaurant, query).andExpect(status().isBadRequest());
		}
	}

	@Test
	void onlyKitchenAndAdminMayReadAndNobodyMayWriteThisPath() throws Exception {
		UUID restaurant = UUID.randomUUID();
		String query = "?date=2025-10-20&timeZone=Europe/Istanbul";

		mvc.perform(get(PATH + query)).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH + query).header("X-Role", "KITCHEN").header("X-Restaurant-Id", restaurant.toString()))
				.andExpect(status().isUnauthorized());
		mvc.perform(get(PATH + query).with(admin(restaurant))).andExpect(status().isOk());
		for (var request : List.of(post(PATH), put(PATH), patch(PATH), delete(PATH))) {
			mvc.perform(request.with(admin(restaurant)).with(csrf())).andExpect(status().isForbidden());
			mvc.perform(request.with(kitchen(restaurant)).with(csrf())).andExpect(status().isMethodNotAllowed());
		}
	}

	@Test
	void anotherRestaurantsNumbersNeverLeakIntoTheReport() throws Exception {
		UUID restaurant = seedIstanbulDay();
		UUID intruder = UUID.randomUUID();

		// The intruder asks for the same day and sees only its own (empty) numbers.
		report(intruder, "?date=2025-10-20&timeZone=Europe/Istanbul").andExpect(jsonPath("$.orders.received").value(0))
				.andExpect(jsonPath("$.waste.totalQuantity").value(0));
		report(restaurant, "?date=2025-10-20&timeZone=Europe/Istanbul").andExpect(jsonPath("$.waste.totalQuantity").value(5));
	}
}
