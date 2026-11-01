package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaiterOrderApiTests extends ApiTestBase {

    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID S1_PREVIOUS = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private FakeBackends.Table t1;
    private FakeBackends.Table t2;
    private FakeBackends.Order preparing;
    private FakeBackends.Order awaiting;
    private FakeBackends.Order servedAndPaid;
    private FakeBackends.Order cancelled;
    private FakeBackends.Order previousSession;
    private FakeBackends.Order notYetInKitchen;
    private FakeBackends.Order otherTableReady;

    @BeforeEach
    void restaurantWithTwoLiveTables() {
        t1 = addTable("1", "OCCUPIED", S1);
        t2 = addTable("2", "OCCUPIED", S2);
        preparing = addOrder(t1, S1, "ORD-1", "ACCEPTED", "PENDING", "PREPARING", "2026-10-16T09:00:00Z");
        awaiting = addOrder(t1, S1, "ORD-2", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:05:00Z");
        servedAndPaid = addOrder(t1, S1, "ORD-3", "COMPLETED", "SERVED", "READY", "2026-10-16T08:00:00Z");
        cancelled = addOrder(t1, S1, "ORD-4", "CANCELLED", "PENDING", "CANCELLED", "2026-10-16T08:30:00Z");
        notYetInKitchen = addOrder(t1, S1, "ORD-5", "ACCEPTED", "PENDING", null, "2026-10-16T09:10:00Z");
        otherTableReady = addOrder(t2, S2, "ORD-6", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:01:00Z");
        previousSession = addOrder(t1, S1_PREVIOUS, "ORD-OLD", "ACCEPTED", "SERVED", "READY", "2026-10-15T20:00:00Z");
        // Another restaurant: same table session ids on purpose.
        FakeBackends.Table foreignTable = addTable(OTHER_RESTAURANT, "1", "OCCUPIED", S1);
        addOrder(foreignTable, S1, "ORD-FOREIGN", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:02:00Z");
    }

    private ResultActions listOrders(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/waiters/orders" + query).with(asWaiter()));
    }

    @Test
    void defaultListCoversOnlyLiveSessionsOfOwnRestaurantNewestFirst() throws Exception {
        listOrders("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.items[*].orderNumber")
                        .value(contains("ORD-5", "ORD-2", "ORD-6", "ORD-1", "ORD-4", "ORD-3")))
                .andExpect(content().string(not(containsString("ORD-OLD"))))
                .andExpect(content().string(not(containsString("ORD-FOREIGN"))));
    }

    @Test
    void readyHistoryIncludesServedOrdersButNotTheQueue() throws Exception {
        // Ready history: Kitchen READY, even if already served; newest ready first.
        listOrders("?view=READY_HISTORY")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-2", "ORD-6", "ORD-3")))
                .andExpect(jsonPath("$.items[?(@.orderNumber=='ORD-3')].deliveryStatus").value(contains("SERVED")));
    }

    @Test
    void awaitingDeliveryIsTheReadyButNotYetServedQueueOldestReadyFirst() throws Exception {
        listOrders("?view=AWAITING_DELIVERY")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-6", "ORD-2")))
                .andExpect(jsonPath("$.items[*].deliveryStatus").value(contains("PENDING", "PENDING")))
                .andExpect(jsonPath("$.items[*].preparation.status").value(contains("READY", "READY")));
    }

    @Test
    void cancelledAndActiveViewsAreExclusive() throws Exception {
        listOrders("?view=CANCELLED")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].orderNumber").value("ORD-4"))
                .andExpect(jsonPath("$.items[0].businessStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].cancellations", hasSize(1)))
                .andExpect(jsonPath("$.items[0].cancellations[0].source").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].cancellations[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].cancellations[0].reasonCode").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.items[0].cancellations[0].reasonText").value("Köfte bitti"));
        listOrders("?view=ACTIVE")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[*].businessStatus").value(contains("ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED")));
    }

    @Test
    void orderThatKitchenHasNotConfirmedIsNeitherReadyNorHiddenAndKeepsAnUnknownPreparation() throws Exception {
        String json = listOrders("").andReturn().getResponse().getContentAsString();
        var unconfirmed = StreamSupport.stream(FakeBackends.read(json).get("items").spliterator(), false)
                .filter(node -> node.get("orderNumber").asString().equals("ORD-5")).findFirst().orElseThrow();
        Assertions.assertTrue(unconfirmed.has("preparation") && unconfirmed.get("preparation").isNull());
        listOrders("?view=READY_HISTORY").andExpect(content().string(not(containsString("ORD-5"))));
        listOrders("?view=AWAITING_DELIVERY").andExpect(content().string(not(containsString("ORD-5"))));
    }

    @Test
    void sessionScopeAllReturnsHistoryAndMarksOldSessionsAsNotCurrent() throws Exception {
        listOrders("?sessionScope=ALL&tableId=" + t1.id())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.items[?(@.orderNumber=='ORD-OLD')].session.current").value(contains(false)))
                .andExpect(jsonPath("$.items[?(@.orderNumber=='ORD-1')].session.current").value(contains(true)))
                .andExpect(content().string(not(containsString("ORD-6"))))
                .andExpect(content().string(not(containsString("ORD-FOREIGN"))));
        listOrders("?sessionScope=ALL&view=READY_HISTORY")
                .andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-2", "ORD-6", "ORD-3", "ORD-OLD")));
    }

    @Test
    void tableFilterNarrowsTheLiveListAndRejectsForeignTables() throws Exception {
        listOrders("?tableId=" + t2.id())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].orderNumber").value("ORD-6"))
                .andExpect(jsonPath("$.items[0].table.tableNumber").value("2"));
        FakeBackends.Table foreign = backends.tables.stream().filter(t -> t.restaurantId().equals(OTHER_RESTAURANT)).findFirst()
                .orElseThrow();
        listOrders("?tableId=" + foreign.id()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_FOUND"));
        listOrders("?tableId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void liveScopeWithoutAnyLiveSessionIsEmptyWithoutAskingOrder() throws Exception {
        backends.tables.removeIf(t -> t.restaurantId().equals(RESTAURANT));
        backends.tables.add(new FakeBackends.Table(UUID.randomUUID(), RESTAURANT, "1", "AVAILABLE", null));
        listOrders("").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertEquals(0, STUB.requests("POST", "/api/v1/internal/orders/search").size());
    }

    @Test
    void pagingKeepsAStableOrderAndTotalsDescribeTheWholeFilteredSet() throws Exception {
        listOrders("?size=2&page=0").andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-5", "ORD-2")))
                .andExpect(jsonPath("$.totalPages").value(3));
        listOrders("?size=2&page=1").andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-6", "ORD-1")));
        listOrders("?size=2&page=2").andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-4", "ORD-3")));
        listOrders("?size=2&page=3").andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalElements").value(6));
        listOrders("?view=READY_HISTORY&size=1&page=2").andExpect(jsonPath("$.items[0].orderNumber").value("ORD-3"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void invalidViewScopeOrPagingIsRejectedBeforeAnyDownstreamCall() throws Exception {
        for (String query : List.of("?view=ALL", "?sessionScope=HISTORY", "?tableId=x", "?size=101", "?page=-1", "?view=READY,ACTIVE")) {
            listOrders(query).andExpect(status().isBadRequest());
        }
        assertEquals(0, STUB.requests().size());
    }

    @Test
    void orderDetailShowsItemsOptionsNoteAndTheFourIndependentStatuses() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/orders/" + awaiting.id()).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(awaiting.id().toString()))
                .andExpect(jsonPath("$.orderNumber").value("ORD-2"))
                .andExpect(jsonPath("$.table.tableNumber").value("1"))
                .andExpect(jsonPath("$.session.id").value(S1.toString()))
                .andExpect(jsonPath("$.session.current").value(true))
                .andExpect(jsonPath("$.businessStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.deliveryStatus").value("PENDING"))
                .andExpect(jsonPath("$.preparation.status").value("READY"))
                .andExpect(jsonPath("$.preparation.asOf").exists())
                .andExpect(jsonPath("$.payment.status").value("NOT_PAID"))
                .andExpect(jsonPath("$.payment.asOf").exists())
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.payableTotal").value(150.00))
                .andExpect(jsonPath("$.items[0].productName").value("Izgara Köfte"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].note").value("Az pişmiş, soğansız"))
                .andExpect(jsonPath("$.items[0].options[0].name").value("Ekstra peynir"))
                .andExpect(jsonPath("$.items[0].options[0].action").value("ADD"))
                .andExpect(jsonPath("$.items[0].options[1].action").value("REMOVE"))
                .andExpect(jsonPath("$.cancellations", hasSize(0)))
                .andExpect(content().string(not(containsString("guestSessionId"))));
    }

    @Test
    void servedOrderKeepsKitchenReadyAndTheDeliveryAxisSaysServed() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/orders/" + servedAndPaid.id()).with(asWaiter()))
                .andExpect(jsonPath("$.deliveryStatus").value("SERVED"))
                .andExpect(jsonPath("$.servedAt").exists())
                .andExpect(jsonPath("$.preparation.status").value("READY"));
    }

    @Test
    void cancelledOrderDetailShowsCancellationInformation() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/orders/" + cancelled.id()).with(asWaiter()))
                .andExpect(jsonPath("$.businessStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledGrossTotal").value(150.00))
                .andExpect(jsonPath("$.items[0].cancelledQuantity").value(2))
                .andExpect(jsonPath("$.cancellations[0].requestedAt").exists())
                .andExpect(jsonPath("$.cancellations[0].decidedAt").exists())
                .andExpect(jsonPath("$.cancellations[0].items[0].quantity").value(2));
    }

    @Test
    void orderOfAnEarlierSessionIsReadableButNotMarkedCurrent() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/orders/" + previousSession.id()).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(S1_PREVIOUS.toString()))
                .andExpect(jsonPath("$.session.current").value(false));
    }

    @Test
    void orderOfAnotherRestaurantLooksLikeAnUnknownOrder() throws Exception {
        FakeBackends.Order foreign = backends.orders.stream().filter(o -> o.restaurantId().equals(OTHER_RESTAURANT)).findFirst()
                .orElseThrow();
        mockMvc.perform(get("/api/v1/waiters/orders/" + foreign.id()).with(asWaiter()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(content().string(not(containsString("ORD-FOREIGN"))));
        mockMvc.perform(get("/api/v1/waiters/orders/" + UUID.randomUUID()).with(asWaiter()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/waiters/orders/not-a-uuid").with(asWaiter())).andExpect(status().isBadRequest());
    }

    @Test
    void orderServiceThatLeaksAnotherTenantsOrderIsNeverRelayed() throws Exception {
        FakeBackends.Order foreign = backends.orders.stream().filter(o -> o.restaurantId().equals(OTHER_RESTAURANT)).findFirst()
                .orElseThrow();
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}",
                request -> DownstreamStub.Response.json(200, FakeBackends.write(FakeBackends.orderJson(foreign))));
        mockMvc.perform(get("/api/v1/waiters/orders/" + foreign.id()).with(asWaiter()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"))
                .andExpect(content().string(not(containsString("ORD-FOREIGN"))));

        STUB.override("POST", "/api/v1/internal/orders/search", request -> DownstreamStub.Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(foreign)), "page", 0, "size", 20, "totalElements", 1))));
        listOrders("").andExpect(status().isBadGateway()).andExpect(content().string(not(containsString("ORD-FOREIGN"))));
    }

    @Test
    void orderServiceThatIgnoresTheRequestedViewIsDetected() throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/search", request -> DownstreamStub.Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(servedAndPaid)), "page", 0, "size", 20, "totalElements", 1))));
        listOrders("?view=AWAITING_DELIVERY").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"));
    }

    @Test
    void requestToOrderCarriesOnlyTheVerifiedRestaurantAndTheAllowListedFilters() throws Exception {
        listOrders("?view=READY_HISTORY&size=5&page=0").andExpect(status().isOk());
        DownstreamStub.Request search = STUB.requests("POST", "/api/v1/internal/orders/search").getFirst();
        var body = FakeBackends.read(search.body());
        assertEquals(RESTAURANT.toString(), body.get("restaurantId").asString());
        assertEquals("READY_HISTORY", body.get("view").asString());
        assertEquals(5, body.get("size").asInt());
        assertEquals(2, body.get("tableSessionIds").size());
        assertEquals(null, search.header("Authorization"), "No credentials are invented for the downstream call");
    }
}
