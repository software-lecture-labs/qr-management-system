package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 4, reporting: the administrator's cancellation report (data only, no admin screen). */
class OrderCancellationReportApiTests extends AbstractCancellationApiTest {

    private static final String REPORT = "/api/v1/admin/order-cancellations";
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    /**
     * Five cancellations of restaurant A: three applied (9,000 + 9,000 + 52,000 cancelled; the paid one requires a
     * refund of 52,000), one waiting for the kitchen and one the kitchen rejected.
     */
    private void fiveCancellations() throws Exception {
        UUID full = placeSaladOrder(A1);
        cancel(guest(A1), full, newKey(), cancelAllWith(List.of("CHANGED_MIND", "WAIT_TOO_LONG"), "left early"))
                .andExpect(status().isCreated());
        UUID partial = placeMixedOrder(DevFixtures.TOKEN_A2);
        cancel(WAITER, partial, newKey(), Map.of("items", List.of(Map.of("orderItemId",
                lineOf(partial, DevFixtures.SALAD), "quantity", 1)), "reasonCodes", List.of("ITEM_UNAVAILABLE")))
                .andExpect(status().isCreated());
        UUID paid = placeAndPayMixedOrder();
        cancelAll(WAITER, paid).andExpect(status().isCreated());
        UUID waiting = placeSaladOrder(A1);
        advanceTo(waiting, "PREPARING");
        cancel(guest(A1), waiting, newKey(), cancelAllWith(List.of("OTHER"), null)).andExpect(status().isCreated());
        UUID rejected = placeSaladOrder(A1);
        advanceTo(rejected, "PREPARING");
        UUID cancellation = requestIdOf(cancelAll(guest(A1), rejected).andExpect(status().isCreated()));
        kitchenDecides(rejected, cancellation, "REJECTED", "KITCHEN_DECLINED").andExpect(status().isOk());
    }

    private String url(String query) {
        return REPORT + (query.isEmpty() ? "" : "?" + query);
    }

    // ---- who may read it --------------------------------------------------------------------------------------------

    @Test
    void onlyTheAdministratorOfTheRestaurantMayReadTheReport() throws Exception {
        getWith(null, REPORT).andExpect(status().isUnauthorized());
        getWith(WAITER, REPORT).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getWith(staff(DevFixtures.STAFF_KITCHEN_A), REPORT).andExpect(status().isForbidden());
        getWith(guest(A1), REPORT).andExpect(status().isForbidden());
        getWith(service(KITCHEN_CREDENTIAL), REPORT).andExpect(status().isForbidden());
        getWith(service(PAYMENT_CREDENTIAL), REPORT).andExpect(status().isForbidden());
        getWith(ADMIN, REPORT).andExpect(status().isOk());
    }

    @Test
    void anAdministratorSeesOnlyTheirOwnRestaurant() throws Exception {
        fiveCancellations();
        UUID other = placeSaladOrder(DevFixtures.TOKEN_B1);
        cancelAll(guest(DevFixtures.TOKEN_B1), other).andExpect(status().isCreated());

        getWith(ADMIN, REPORT).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(5));
        getWith(staff(DevFixtures.STAFF_ADMIN_B), REPORT).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(other.toString()))
                .andExpect(jsonPath("$.summary.totals[0].cancelledGrossMinor").value(SALAD_DUE));
    }

    @Test
    void anEmptyReportIsAnEmptyPage() throws Exception {
        getWith(ADMIN, REPORT).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.summary.totalCount").value(0))
                .andExpect(jsonPath("$.summary.totals.length()").value(0))
                .andExpect(jsonPath("$.summary.withoutReasonCount").value(0));
    }

    // ---- content ----------------------------------------------------------------------------------------------------

    @Test
    void theSummaryKeepsCancelledRequiredAndRefundedMoneyApartAndCountsReasons() throws Exception {
        fiveCancellations();

        getWith(ADMIN, REPORT).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.summary.totalCount").value(5))
                .andExpect(jsonPath("$.summary.byStatus.APPLIED").value(3))
                .andExpect(jsonPath("$.summary.byStatus.REQUESTED").value(1))
                .andExpect(jsonPath("$.summary.byStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.summary.totals.length()").value(1))
                .andExpect(jsonPath("$.summary.totals[0].currency").value("TRY"))
                .andExpect(jsonPath("$.summary.totals[0].appliedCount").value(3))
                .andExpect(jsonPath("$.summary.totals[0].cancelledGrossMinor").value(70_000))
                .andExpect(jsonPath("$.summary.totals[0].refundRequiredMinor").value(MIXED_TOTAL))
                .andExpect(jsonPath("$.summary.totals[0].refundedMinor").value(0))
                .andExpect(jsonPath("$.summary.withoutReasonCount").value(2))
                .andExpect(jsonPath("$.summary.byReason.length()").value(4))
                .andExpect(jsonPath("$.summary.byReason[?(@.code=='CHANGED_MIND')].count").value(1))
                .andExpect(jsonPath("$.summary.byReason[?(@.code=='CHANGED_MIND')].group").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.summary.byReason[?(@.code=='ITEM_UNAVAILABLE')].group").value("OUT_OF_STOCK"));
    }

    @Test
    void eachItemTellsWhoWhatWhyAndWhatItMeantForTheMoney() throws Exception {
        fiveCancellations();

        JsonNode report = body(getWith(ADMIN, url("status=APPLIED&scope=FULL&reasonCode=changed_mind")));
        assertEquals(1, report.path("totalItems").asInt());
        JsonNode item = report.path("items").get(0);
        assertEquals("FULL", item.path("scope").asString());
        assertEquals("APPLIED", item.path("status").asString());
        assertEquals("AUTO", item.path("decisionSource").asString());
        assertEquals("GUEST", item.path("requestedBy").path("type").asString());
        assertTrue(item.path("requestedBy").path("id").isMissingNode(), "a guest identifier is never in the report");
        assertEquals(SALAD_DUE, item.path("amounts").path("cancelledGrossMinor").asLong());
        assertEquals(1, item.path("unitsInRequest").asInt());
        assertEquals(false, item.path("reasonPending").asBoolean());
        assertEquals(3, item.path("reasons").size());
        assertTrue(item.path("orderNo").asString().startsWith("ORD-"));

        JsonNode paid = body(getWith(ADMIN, url("status=APPLIED&scope=FULL"))).path("items");
        JsonNode staffRequest = null;
        for (JsonNode candidate : paid) {
            if (candidate.path("amounts").path("refundRequiredMinor").asLong() > 0) {
                staffRequest = candidate;
            }
        }
        Assertions.assertNotNull(staffRequest, "the cancelled paid order is in the report");
        assertEquals("STAFF", staffRequest.path("requestedBy").path("type").asString());
        assertEquals("WAITER", staffRequest.path("requestedBy").path("role").asString());
        assertEquals(DevFixtures.USER_WAITER_A.toString(), staffRequest.path("requestedBy").path("id").asString());
        assertEquals(MIXED_TOTAL, staffRequest.path("amounts").path("collectedMinor").asLong());
        assertEquals(MIXED_TOTAL, staffRequest.path("amounts").path("refundRequiredMinor").asLong());
        assertEquals(0L, staffRequest.path("amounts").path("refundedMinor").asLong());
        assertEquals(true, staffRequest.path("reasonPending").asBoolean());
    }

    @Test
    void aRejectedRequestShowsItsCodeAndNoCancelledMoney() throws Exception {
        fiveCancellations();

        getWith(ADMIN, url("status=REJECTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rejectionCode").value("KITCHEN_DECLINED"))
                .andExpect(jsonPath("$.items[0].decisionSource").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].amounts.cancelledGrossMinor").value(0))
                .andExpect(jsonPath("$.summary.totals.length()").value(0));
        getWith(ADMIN, url("status=REQUESTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kitchenConfirmationRequired").value(true))
                .andExpect(jsonPath("$.items[0].decidedAt").doesNotExist());
    }

    // ---- filters ----------------------------------------------------------------------------------------------------

    @Test
    void theFiltersNarrowThePageAndTheSummary() throws Exception {
        fiveCancellations();

        getWith(ADMIN, url("scope=PARTIAL")).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.summary.totals[0].cancelledGrossMinor").value(9_000));
        getWith(ADMIN, url("reasonCode=OTHER")).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REQUESTED"));
        getWith(ADMIN, url("reasonCode=ITEM_UNAVAILABLE&status=APPLIED")).andExpect(jsonPath("$.totalItems").value(1));
        getWith(ADMIN, url("reasonCode=KITCHEN_ERROR")).andExpect(jsonPath("$.totalItems").value(0));
        getWith(ADMIN, url("status=APPLIED&scope=PARTIAL&reasonCode=CHANGED_MIND")).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void theTimeRangeFiltersOnWhenTheCancellationWasRequested() throws Exception {
        fiveCancellations();
        Instant now = Instant.now();
        String hourAgo = now.minus(1, ChronoUnit.HOURS).toString();
        String hourAhead = now.plus(1, ChronoUnit.HOURS).toString();

        getWith(ADMIN, url("from=" + hourAgo + "&to=" + hourAhead)).andExpect(jsonPath("$.totalItems").value(5));
        getWith(ADMIN, url("from=" + hourAhead)).andExpect(jsonPath("$.totalItems").value(0));
        getWith(ADMIN, url("to=" + hourAgo)).andExpect(jsonPath("$.totalItems").value(0));
        getWith(ADMIN, url("from=" + now.minus(2, ChronoUnit.DAYS).toString().substring(0, 10)))
                .andExpect(jsonPath("$.totalItems").value(5));
        // from is inclusive, to is exclusive
        String firstRequest = jdbc.queryForObject("SELECT min(requested_at) FROM order_cancellations", Timestamp.class)
                .toInstant().toString();
        getWith(ADMIN, url("from=" + firstRequest)).andExpect(jsonPath("$.totalItems").value(5));
        getWith(ADMIN, url("to=" + firstRequest)).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void badFiltersAreRefusedAndNeverReachTheQuery() throws Exception {
        for (String bad : List.of("status=DONE", "scope=HALF", "reasonCode=NOPE", "from=yesterday", "to=2026-13-45",
                "from=2026-10-02&to=2026-10-01", "from=2026-10-01&to=2026-10-01", "page=-1", "limit=0", "limit=101",
                "page=abc", "status=%27%20OR%201%3D1%20--", "reasonCode=OTHER%27%3B%20DROP%20TABLE%20orders%3B--")) {
            getWith(ADMIN, url(bad)).andExpect(result -> assertTrue(result.getResponse().getStatus() == 422
                    || result.getResponse().getStatus() == 400, bad + " -> " + result.getResponse().getStatus()));
        }
        assertEquals(0, count("order_cancellations"));
        getWith(ADMIN, url("")).andExpect(status().isOk());
    }

    @Test
    void pagingIsStableAndCoversEveryRowOnce() throws Exception {
        fiveCancellations();
        Set<String> seen = new HashSet<>();
        List<String> times = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = body(getWith(ADMIN, url("limit=2&page=" + page)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(5)).andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.limit").value(2)));
            for (JsonNode item : body.path("items")) {
                assertTrue(seen.add(item.path("cancellationId").asString()), "no row twice");
                times.add(item.path("requestedAt").asString());
            }
            assertEquals(page < 2 ? 2 : 1, body.path("items").size());
        }
        assertEquals(5, seen.size());
        List<String> newestFirst = new ArrayList<>(times);
        newestFirst.sort(Comparator.reverseOrder());
        assertEquals(newestFirst, times, "newest first");
        getWith(ADMIN, url("limit=2&page=9")).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(5));
    }

    // ---- the records are the source of truth -----------------------------------------------------------------------

    @Test
    void theReportDoesNotDependOnTheOutboxAndSurvivesItsCleanup() throws Exception {
        fiveCancellations();
        // every event of every order is delivered long ago and then cleaned up
        jdbc.update("UPDATE outbox_deliveries SET status = 'DELIVERED', attempt_count = 1, delivered_at = ?",
                Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        int removed = retention.purgeDeliveredOutboxEvents();

        assertTrue(removed > 0, "the cleanup really ran");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type LIKE 'OrderCancel%'",
                Integer.class), "the cancellation events are gone from the outbox");
        getWith(ADMIN, REPORT).andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.summary.totals[0].cancelledGrossMinor").value(70_000))
                .andExpect(jsonPath("$.summary.byReason.length()").value(4));
        assertEquals(5, count("order_cancellations"));
    }

    @Autowired
    private DataRetentionService retention;
}
