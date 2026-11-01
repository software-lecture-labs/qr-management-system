package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 2: listing orders by table session / table, views, paging and who may see which list. */
class OrderListApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final String B1 = DevFixtures.TOKEN_B1;
    private static final String LIST = "/api/v1/orders";

    private final AtomicInteger numbers = new AtomicInteger(5000);

    private JsonNode list(String authorization, String query) throws Exception {
        return json.readTree(getWith(authorization, LIST + query).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("orderId").asString()));
        return result;
    }

    /** Inserts an order row directly (fast, and lets a test choose status and time precisely). */
    private UUID insertOrder(UUID restaurant, UUID table, UUID tableSession, UUID guestSession, String fulfillment,
                             String payment, Instant placedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id,"
                        + " guest_session_id, fulfillment_status, payment_status, currency, initial_net_minor,"
                        + " initial_vat_minor, initial_gross_minor, current_due_minor, placed_at, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TRY', 10, 0, 10, 10, ?, 0, ?, ?)",
                id, restaurant, "ORD-" + numbers.incrementAndGet(), UUID.randomUUID(), table, tableSession,
                guestSession, fulfillment, payment, Timestamp.from(placedAt), Timestamp.from(placedAt),
                Timestamp.from(placedAt));
        return id;
    }

    private UUID insertA1(String fulfillment, String payment, Instant placedAt) {
        return insertOrder(DevFixtures.RESTAURANT_A, DevFixtures.TABLE_A1, DevFixtures.TABLE_SESSION_A1,
                DevFixtures.GUEST_A1, fulfillment, payment, placedAt);
    }

    // ---- scope: who sees which orders ----------------------------------------------------------------------------

    @Test
    void aGuestSeesOnlyTheirOwnOrdersNewestFirstEvenAtASharedTable() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(A1);
        UUID other = placeSaladOrder(A2);

        JsonNode mine = list(guest(A1), "");
        assertEquals(List.of(second.toString(), first.toString()), ids(mine), "newest first");
        assertEquals(2, mine.path("totalItems").asInt());
        assertEquals("active", mine.path("view").asString());
        assertEquals(List.of(other.toString()), ids(list(guest(A2), "")));
        assertEquals(List.of(), ids(list(guest(B1), "")), "another restaurant sees nothing of restaurant A");
    }

    @Test
    void theSummaryCarriesWhatAListNeedsAndNothingSensitive() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        JsonNode summary = list(guest(A1), "").path("items").get(0);
        assertEquals(orderId.toString(), summary.path("orderId").asString());
        assertEquals("ORD-000001", summary.path("orderNo").asString());
        assertEquals(DevFixtures.TABLE_A1.toString(), summary.path("tableId").asString());
        assertEquals(DevFixtures.TABLE_SESSION_A1.toString(), summary.path("tableSessionId").asString());
        assertEquals("RECEIVED", summary.path("fulfillmentStatus").asString());
        assertEquals("NOT_REQUESTED", summary.path("paymentStatus").asString());
        assertEquals(9_000, summary.path("grossMinor").asLong());
        assertEquals(9_000, summary.path("currentDueMinor").asLong());
        assertEquals(1, summary.path("unitCount").asLong());
        assertTrue(summary.has("placedAt"));
        String raw = summary.toString();
        for (String hidden : new String[]{"guestSessionId", "cartId", "items", "restaurantId"}) {
            assertFalse(raw.contains(hidden), "the summary must not contain " + hidden);
        }
    }

    @Test
    void aGuestCannotWidenTheirListWithTheFilters() throws Exception {
        UUID mine = placeSaladOrder(A1);
        placeSaladOrder(A2);
        // an own session id is fine and changes nothing
        assertEquals(List.of(mine.toString()), ids(list(guest(A1), "?tableSessionId=" + DevFixtures.TABLE_SESSION_A1)));
        assertEquals(List.of(mine.toString()), ids(list(guest(A1), "?tableId=" + DevFixtures.TABLE_A1)));
        // someone else's session / table is refused, not silently ignored
        for (String foreign : new String[]{"?tableSessionId=" + DevFixtures.TABLE_SESSION_B1,
                "?tableSessionId=" + UUID.randomUUID(), "?tableId=" + DevFixtures.TABLE_B1}) {
            getWith(guest(A1), LIST + foreign).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        // a restaurant-B guest asking for restaurant A's table session
        getWith(guest(B1), LIST + "?tableSessionId=" + DevFixtures.TABLE_SESSION_A1).andExpect(status().isForbidden());
    }

    @Test
    void identityHintsInHeadersChangeNothing() throws Exception {
        placeSaladOrder(A1);
        mvc.perform(MockMvcRequestBuilders.get(LIST)
                        .header("Authorization", guest(A2)).header("X-Guest-Session-Id", DevFixtures.GUEST_A1.toString())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void aGuestWhoseSessionEndedGetsNoList() throws Exception {
        placeSaladOrder(A1);
        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            getWith(guest(A1), LIST).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.items").doesNotExist());
        }
    }

    @Test
    void adminAndWaiterListTheirWholeRestaurantAndNobodyElseDoes() throws Exception {
        UUID a1 = placeSaladOrder(A1);
        UUID a2 = placeSaladOrder(A2);
        UUID b1 = placeSaladOrder(B1);

        for (String credential : List.of(DevFixtures.STAFF_ADMIN_A, DevFixtures.STAFF_WAITER_A)) {
            Set<String> seen = new HashSet<>(ids(list(staff(credential), "")));
            assertEquals(Set.of(a1.toString(), a2.toString()), seen);
        }
        assertEquals(List.of(b1.toString()), ids(list(staff(DevFixtures.STAFF_ADMIN_B), "")));
        assertEquals(Set.of(a1.toString(), a2.toString()),
                new HashSet<>(ids(list(staff(DevFixtures.STAFF_KITCHEN_A), ""))), "KITCHEN sees its own restaurant");
        getWith(staff("unknown-credential"), LIST).andExpect(status().isUnauthorized());
        getWith(null, LIST).andExpect(status().isUnauthorized());
    }

    @Test
    void staffAskingForAnotherRestaurantsTableSessionGetsNothingNotAnError() throws Exception {
        placeSaladOrder(B1);
        JsonNode page = list(staff(DevFixtures.STAFF_ADMIN_A), "?tableSessionId=" + DevFixtures.TABLE_SESSION_B1);
        assertEquals(0, page.path("totalItems").asInt());
        assertEquals(List.of(), ids(page));
    }

    // ---- table vs table session -----------------------------------------------------------------------------------

    @Test
    void aTableAndItsSessionsAreDifferentThings() throws Exception {
        UUID oldVisit = placeSaladOrder(A1);
        // the table is used again later: same physical table, a NEW table session and a new guest
        UUID newSession = UUID.randomUUID();
        UUID newGuest = UUID.randomUUID();
        qr.register("next-visit", new GuestSessionContext(DevFixtures.RESTAURANT_A, DevFixtures.TABLE_A1, newSession,
                newGuest, SessionState.ACTIVE));
        UUID newVisit = placeSaladOrder("next-visit");

        String staffAdmin = staff(DevFixtures.STAFF_ADMIN_A);
        assertEquals(Set.of(oldVisit.toString(), newVisit.toString()),
                new HashSet<>(ids(list(staffAdmin, "?tableId=" + DevFixtures.TABLE_A1))), "the table over time");
        assertEquals(List.of(newVisit.toString()), ids(list(staffAdmin, "?tableSessionId=" + newSession)));
        assertEquals(List.of(oldVisit.toString()),
                ids(list(staffAdmin, "?tableSessionId=" + DevFixtures.TABLE_SESSION_A1)));
        // a session id is not a table id and vice versa
        assertEquals(List.of(), ids(list(staffAdmin, "?tableId=" + newSession)));
        assertEquals(List.of(), ids(list(staffAdmin, "?tableSessionId=" + DevFixtures.TABLE_A1)));
        // the new guest never sees the previous visit's order, the old guest never sees the new one
        assertEquals(List.of(newVisit.toString()), ids(list(guest("next-visit"), "")));
        assertEquals(List.of(oldVisit.toString()), ids(list(guest(A1), "")));
    }

    // ---- views: fulfillment decides, payment is independent ---------------------------------------------------------

    @Test
    void activeCompletedAndCancelledAreDefinedByTheFulfillmentStatusOnly() throws Exception {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID received = insertA1("RECEIVED", "NOT_REQUESTED", t.plusSeconds(1));
        UUID preparing = insertA1("PREPARING", "NOT_REQUESTED", t.plusSeconds(2));
        UUID ready = insertA1("READY", "NOT_REQUESTED", t.plusSeconds(3));
        UUID served = insertA1("SERVED", "NOT_REQUESTED", t.plusSeconds(4));
        UUID completed = insertA1("COMPLETED", "PAID", t.plusSeconds(5));
        UUID cancelled = insertA1("CANCELLED", "CANCELLED", t.plusSeconds(6));

        assertEquals(Set.of(received, preparing, ready, served).stream().map(UUID::toString).collect(Collectors.toSet()),
                new HashSet<>(ids(list(guest(A1), "?view=active"))));
        assertEquals(List.of(completed.toString()), ids(list(guest(A1), "?view=completed")));
        assertEquals(List.of(cancelled.toString()), ids(list(guest(A1), "?view=cancelled")));
        // default is active; the parameter is case-insensitive
        assertEquals(4, list(guest(A1), "").path("totalItems").asInt());
        assertEquals(List.of(completed.toString()), ids(list(guest(A1), "?view=COMPLETED")));
        assertEquals("completed", list(guest(A1), "?view=completed").path("view").asString());
    }

    @Test
    void aCompletedOrderCanBeUnpaidAndAPaidOrderCanStillBeActive() throws Exception {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID completedUnpaid = insertA1("COMPLETED", "PENDING", t.plusSeconds(1));
        UUID completedPaid = insertA1("COMPLETED", "PAID", t.plusSeconds(2));
        UUID preparingButPaid = insertA1("PREPARING", "PAID", t.plusSeconds(3));

        assertEquals(Set.of(completedUnpaid.toString(), completedPaid.toString()),
                new HashSet<>(ids(list(guest(A1), "?view=completed"))));
        assertEquals(List.of(completedUnpaid.toString()),
                ids(list(guest(A1), "?view=completed&paymentStatus=PENDING")));
        assertEquals(List.of(preparingButPaid.toString()), ids(list(guest(A1), "?view=active")),
                "PAID does not make an order completed");
        assertEquals(List.of(preparingButPaid.toString()), ids(list(guest(A1), "?view=active&paymentStatus=paid")));
        assertEquals(List.of(), ids(list(guest(A1), "?view=active&paymentStatus=PENDING")));
    }

    // ---- paging and sorting -----------------------------------------------------------------------------------------

    @Test
    void pagingWalksEveryOrderExactlyOnceInAStableOrder() throws Exception {
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS);
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(insertA1("RECEIVED", "NOT_REQUESTED", t.plusSeconds(i)));
        }
        List<String> newestFirst = new ArrayList<>();
        for (int i = created.size() - 1; i >= 0; i--) {
            newestFirst.add(created.get(i).toString());
        }

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = list(guest(A1), "?limit=2&page=" + page);
            assertEquals(5, result.path("totalItems").asInt());
            assertEquals(3, result.path("totalPages").asInt());
            assertEquals(2, result.path("limit").asInt());
            assertEquals(page, result.path("page").asInt());
            walked.addAll(ids(result));
        }
        assertEquals(newestFirst, walked);

        List<String> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        List<String> ascending = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ascending.addAll(ids(list(guest(A1), "?limit=2&sort=placedAt,asc&page=" + page)));
        }
        assertEquals(oldestFirst, ascending);

        JsonNode beyond = list(guest(A1), "?limit=2&page=9");
        assertEquals(List.of(), ids(beyond));
        assertEquals(5, beyond.path("totalItems").asInt(), "the total is still reported past the end");
    }

    @Test
    void ordersPlacedAtTheSameInstantNeverDisappearOrRepeatAcrossPages() throws Exception {
        Instant same = Instant.now().minus(3, ChronoUnit.HOURS);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            expected.add(insertA1("RECEIVED", "NOT_REQUESTED", same).toString());
        }
        for (int run = 0; run < 2; run++) {
            List<String> walked = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                walked.addAll(ids(list(guest(A1), "?limit=2&page=" + page)));
            }
            assertEquals(7, walked.size());
            assertEquals(expected, new HashSet<>(walked), "no order skipped or repeated");
        }
    }

    @Test
    void theLimitIsCappedAtOneHundredAndLargeListsPage() throws Exception {
        Instant t = Instant.now().minus(4, ChronoUnit.HOURS);
        for (int i = 0; i < 130; i++) {
            insertA1("RECEIVED", "NOT_REQUESTED", t.plusSeconds(i));
        }
        JsonNode full = list(guest(A1), "?limit=100");
        assertEquals(100, full.path("items").size());
        assertEquals(130, full.path("totalItems").asInt());
        assertEquals(2, full.path("totalPages").asInt());
        assertEquals(30, list(guest(A1), "?limit=100&page=1").path("items").size());
        assertEquals(20, list(guest(A1), "").path("items").size(), "the default page size is 20");
    }

    @Test
    void badParametersAreRejectedAndNamed() throws Exception {
        for (String[] bad : new String[][]{{"limit=0", "limit"}, {"limit=101", "limit"}, {"limit=-5", "limit"},
                {"page=-1", "page"}, {"view=all", "view"}, {"view=", "view"}, {"sort=price,desc", "sort"},
                {"sort=placedAt;drop table orders", "sort"}, {"paymentStatus=REFUNDED", "paymentStatus"}}) {
            getWith(guest(A1), LIST + "?" + bad[0])
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details.parameter").value(bad[1]));
        }
        for (String malformed : new String[]{"tableSessionId=not-a-uuid", "tableId=123", "page=abc", "limit=1.5"}) {
            getWith(guest(A1), LIST + "?" + malformed).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theIndexesThatServeTheListsExist() {
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'orders'",
                String.class);
        for (String expected : new String[]{"ix_orders_table", "ix_orders_table_session", "ix_orders_guest_session",
                "ix_orders_status"}) {
            assertTrue(indexes.contains(expected), expected + " must exist: " + indexes);
        }
    }
}
