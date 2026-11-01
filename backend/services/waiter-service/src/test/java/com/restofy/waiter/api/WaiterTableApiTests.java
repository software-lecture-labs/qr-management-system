package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaiterTableApiTests extends ApiTestBase {

    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID S1_PREVIOUS = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID S10 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID S_CLEANING = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private FakeBackends.Table t1;
    private FakeBackends.Table t2;
    private FakeBackends.Table t10;

    @BeforeEach
    void restaurantWithFiveTables() {
        // Inserted out of order on purpose: the API order must be natural (1, 2, 10, 11, A1).
        addTable("A1", "AVAILABLE", null);
        addTable("11", "CLEANING", S_CLEANING);
        t10 = addTable("10", "OCCUPIED", S10);
        t2 = addTable("2", "AVAILABLE", null);
        t1 = addTable("1", "OCCUPIED", S1);
        // Foreign restaurant data that must never show up.
        addTable(OTHER_RESTAURANT, "1", "OCCUPIED", UUID.randomUUID());

        // S1: two active orders. S10 is occupied but has no order yet: occupancy comes from QR, not from orders.
        addOrder(t1, S1, "ORD-1", "ACCEPTED", "PENDING", "PREPARING", "2026-10-16T09:00:00Z");
        addOrder(t1, S1, "ORD-2", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:05:00Z");
        addOrder(t1, S1, "ORD-3", "COMPLETED", "SERVED", "READY", "2026-10-16T08:00:00Z");
        // Previous session of table 1 (closed): its order and call must not leak into the live session.
        addOrder(t1, S1_PREVIOUS, "ORD-OLD", "ACCEPTED", "PENDING", "READY", "2026-10-15T20:00:00Z");

        insertServiceRequest(RESTAURANT, S1, "WAITER", "OPEN");
        insertServiceRequest(RESTAURANT, S1, "HELP", "RESOLVED");
        insertServiceRequest(RESTAURANT, S1_PREVIOUS, "HELP", "OPEN");
        insertServiceRequest(RESTAURANT, S10, "BILL", "CLAIMED");
        insertServiceRequest(OTHER_RESTAURANT, S1, "BILL", "OPEN");
    }

    private ResultActions listTables(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/waiters/tables" + query).with(asWaiter()));
    }

    @Test
    void listsEveryTableOfTheOwnRestaurantInNaturalOrderWithIndependentFacts() throws Exception {
        listTables("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].tableNumber").value(contains("1", "2", "10", "11", "A1")))
                .andExpect(jsonPath("$.items[0].operationalStatus").value("OCCUPIED"))
                .andExpect(jsonPath("$.items[0].session.id").value(S1.toString()))
                .andExpect(jsonPath("$.items[0].activeOrderCount").value(2))
                .andExpect(jsonPath("$.items[0].openCallCount").value(1))
                .andExpect(jsonPath("$.items[0].billRequested").value(false))
                .andExpect(jsonPath("$.items[2].tableNumber").value("10"))
                .andExpect(jsonPath("$.items[2].activeOrderCount").value(0))
                .andExpect(jsonPath("$.items[2].billRequested").value(true))
                .andExpect(jsonPath("$.items[1].session").value(nullValue()));
    }

    @Test
    void occupiedTableWithoutOrdersIsStillOccupied() throws Exception {
        listTables("?status=OCCUPIED&hasActiveOrder=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].tableNumber").value(contains("10")))
                .andExpect(jsonPath("$.items[0].operationalStatus").value("OCCUPIED"))
                .andExpect(jsonPath("$.items[0].activeOrderCount").value(0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "?status=OCCUPIED|1,10",
            "?status=AVAILABLE|2,A1",
            "?status=CLEANING|11",
            "?hasActiveOrder=true|1",
            "?hasActiveOrder=false|2,10,11,A1",
            "?hasOpenCall=true|1",
            "?hasOpenCall=false|2,10,11,A1",
            "?billRequested=true|10",
            "?billRequested=false|1,2,11,A1",
            "?status=OCCUPIED&billRequested=true|10",
            "?hasActiveOrder=true&billRequested=true|"})
    void filtersCombineWithAnd(String query, String expectedNumbers) throws Exception {
        String[] expected = expectedNumbers == null || expectedNumbers.isEmpty() ? new String[0] : expectedNumbers.split(",");
        listTables(query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expected.length))
                .andExpect(jsonPath("$.items[*].tableNumber").value(expected.length == 0
                        ? Matchers.empty() : Matchers.contains((Object[]) expected)));
    }

    @Test
    void callsOfAnEarlierSessionNeverCountForTheLiveSession() throws Exception {
        // S1_PREVIOUS has an open HELP call, but table 1 now runs session S1 whose HELP call is resolved.
        listTables("?hasOpenCall=true")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].openCallCount").value(1));
        mockMvc.perform(get("/api/v1/waiters/tables/" + t1.id()).with(asWaiter()))
                .andExpect(jsonPath("$.openServiceRequests", hasSize(1)))
                .andExpect(jsonPath("$.openServiceRequests[0].type").value("WAITER"));
    }

    @Test
    void paginationAndTotalsDescribeTheFilteredSetAcrossPages() throws Exception {
        listTables("?size=2&page=0").andExpect(jsonPath("$.items[*].tableNumber").value(contains("1", "2")))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        listTables("?size=2&page=1").andExpect(jsonPath("$.items[*].tableNumber").value(contains("10", "11")));
        listTables("?size=2&page=2").andExpect(jsonPath("$.items[*].tableNumber").value(contains("A1")));
        listTables("?size=2&page=3").andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalElements").value(5));
        listTables("?status=AVAILABLE&size=1&page=1").andExpect(jsonPath("$.items[*].tableNumber").value(contains("A1")))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void filtersSeeTablesFromEveryDownstreamPageAndNeverCallPerTable() throws Exception {
        backends.tables.clear();
        backends.orders.clear();
        for (int number = 1; number <= 250; number++) {
            boolean occupied = number == 3 || number == 120 || number == 201 || number == 230 || number == 250;
            addTable(String.valueOf(number), occupied ? "OCCUPIED" : "AVAILABLE", occupied ? UUID.randomUUID() : null);
        }
        listTables("?status=OCCUPIED&size=2&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[*].tableNumber").value(contains("201", "230")));
        assertEquals(2, STUB.requests("GET", "/api/v1/internal/tables").size(), "250 tables need exactly two QR pages");
        assertEquals(1, STUB.requests("POST", "/api/v1/internal/orders/session-summaries").size(),
                "One batch request for all sessions, not one request per table");
        assertEquals(0, STUB.requests("GET", "/api/v1/internal/tables/[0-9a-f-]{36}").size());
    }

    @Test
    void tableDetailShowsSessionOrdersCountAndUnresolvedCalls() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + t10.id()).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.tableNumber").value("10"))
                .andExpect(jsonPath("$.table.capacity").value(4))
                .andExpect(jsonPath("$.table.session.scanCount").value(2))
                .andExpect(jsonPath("$.table.activeOrderCount").value(0))
                .andExpect(jsonPath("$.table.billRequested").value(true))
                .andExpect(jsonPath("$.openServiceRequests", hasSize(1)))
                .andExpect(jsonPath("$.openServiceRequests[0].type").value("BILL"))
                .andExpect(jsonPath("$.openServiceRequests[0].status").value("CLAIMED"))
                .andExpect(jsonPath("$.openServiceRequests[0].guestSessionId").doesNotExist());
    }

    @Test
    void availableTableDetailHasNoSessionAndNoCalls() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + t2.id()).with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.session").value(nullValue()))
                .andExpect(jsonPath("$.openServiceRequests", hasSize(0)));
        assertEquals(0, STUB.requests("POST", "/api/v1/internal/orders/.*").size(), "No session, so nothing to ask Order");
    }

    @Test
    void tableOfAnotherRestaurantLooksLikeAnUnknownTable() throws Exception {
        FakeBackends.Table foreign = backends.tables.stream().filter(t -> t.restaurantId().equals(OTHER_RESTAURANT)).findFirst()
                .orElseThrow();
        mockMvc.perform(get("/api/v1/waiters/tables/" + foreign.id()).with(asWaiter()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TABLE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/waiters/tables/" + UUID.randomUUID()).with(asWaiter()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TABLE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/waiters/tables/" + foreign.id() + "/orders").with(asWaiter()))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedPathIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/not-a-uuid").with(asWaiter()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?status=FREE", "?status=OCCUPIED,AVAILABLE", "?hasActiveOrder=maybe", "?hasOpenCall=1x",
            "?page=-1", "?page=abc", "?size=0", "?size=101", "?size=-5", "?size=x"})
    void invalidFilterOrPagingValuesAreRejected(String query) throws Exception {
        listTables(query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0, STUB.requests().size(), "Validation happens before any downstream call");
    }

    @Test
    void maximumPageSizeIsAccepted() throws Exception {
        listTables("?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void tableOrdersListsOnlyTheLiveSessionOfThatTable() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + t1.id() + "/orders").with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].orderNumber").value(contains("ORD-2", "ORD-1", "ORD-3")))
                .andExpect(jsonPath("$.items[*].session.current").value(contains(true, true, true)))
                .andExpect(jsonPath("$.items[*].table.id").value(contains(t1.id().toString(), t1.id().toString(), t1.id().toString())));
        DownstreamStub.Request search = STUB.requests("POST", "/api/v1/internal/orders/search").getFirst();
        var body = FakeBackends.read(search.body());
        assertEquals(RESTAURANT.toString(), body.get("restaurantId").asString());
        assertEquals(t1.id().toString(), body.get("tableId").asString());
        assertEquals(1, body.get("tableSessionIds").size());
        assertEquals(S1.toString(), body.get("tableSessionIds").get(0).asString());
    }

    @Test
    void tableOrdersKeepsSeparateOrderIdsForTheSameTable() throws Exception {
        String json = mockMvc.perform(get("/api/v1/waiters/tables/" + t1.id() + "/orders").with(asWaiter()))
                .andReturn().getResponse().getContentAsString();
        var items = FakeBackends.read(json).get("items");
        assertEquals(3, items.size());
        assertEquals(3, StreamSupport.stream(items.spliterator(), false)
                .map(node -> node.get("id").asString()).distinct().count());
    }

    @Test
    void tableOrdersViewIsForwardedAndFiltersTheWholeSet() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + t1.id() + "/orders?view=AWAITING_DELIVERY").with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].orderNumber").value("ORD-2"));
    }

    @Test
    void tableWithoutLiveSessionHasNoOrdersAndOrderIsNotAsked() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables/" + t2.id() + "/orders").with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertEquals(0, STUB.requests("POST", "/api/v1/internal/orders/search").size());
    }

    @Test
    void ordersFromAnEarlierSessionReturnedByAMisbehavingOrderServiceAreRejected() throws Exception {
        FakeBackends.Order old = backends.orders.stream().filter(o -> o.number().equals("ORD-OLD")).findFirst().orElseThrow();
        STUB.override("POST", "/api/v1/internal/orders/search", request -> DownstreamStub.Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(old)), "page", 0, "size", 20, "totalElements", 1))));
        mockMvc.perform(get("/api/v1/waiters/tables/" + t1.id() + "/orders").with(asWaiter()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("ORD-OLD"))));
    }
}
