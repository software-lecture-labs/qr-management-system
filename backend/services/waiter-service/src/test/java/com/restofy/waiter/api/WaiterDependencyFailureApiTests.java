package com.restofy.waiter.api;

import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub.Response;
import com.restofy.waiter.support.FakeBackends;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A dependency problem must never look like "no tables" or "no orders": every failure kind has its own status
 * and code, requests are never repeated, and no internal address leaks into the error body.
 */
class WaiterDependencyFailureApiTests extends ApiTestBase {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private FakeBackends.Table table;
    private FakeBackends.Order order;

    @BeforeEach
    void oneOccupiedTableWithOneOrder() {
        table = addTable("1", "OCCUPIED", SESSION);
        order = addOrder(table, SESSION, "ORD-1", "ACCEPTED", "PENDING", "READY", "2026-10-16T09:00:00Z");
    }

    private ResultActions get(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path).with(asWaiter()));
    }

    private void assertSafeBody(ResultActions result) throws Exception {
        result.andExpect(content().string(not(containsString("127.0.0.1"))))
                .andExpect(content().string(not(containsString("localhost"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com."))))
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void qrTimeoutIsAGatewayTimeoutNotAnEmptyTableList() throws Exception {
        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, "{}").delayed(2500));
        ResultActions result = get("/api/v1/waiters/tables")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"))
                .andExpect(jsonPath("$.dependency").value("qr"))
                .andExpect(jsonPath("$.items").doesNotExist());
        assertSafeBody(result);
        assertEquals(1, STUB.requests("GET", "/api/v1/internal/tables").size(), "A timed-out request must not be repeated");
    }

    @Test
    void qrServerErrorIsServiceUnavailable() throws Exception {
        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(500, "{\"trace\":\"secret internal stack\"}"));
        ResultActions result = get("/api/v1/waiters/tables")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("qr"));
        assertSafeBody(result);
        result.andExpect(content().string(not(containsString("secret internal stack"))));
        assertEquals(1, STUB.requests("GET", "/api/v1/internal/tables").size());
    }

    @Test
    void orderOutageOnTheTableListDoesNotTurnIntoZeroActiveOrders() throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/session-summaries", request -> Response.json(503, "{}"));
        assertSafeBody(get("/api/v1/waiters/tables")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("order")));
        get("/api/v1/waiters/tables/" + table.id()).andExpect(status().isServiceUnavailable());
    }

    @Test
    void orderTimeoutIsAGatewayTimeoutOnOrderListsAndTableOrders() throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/search", request -> Response.json(200, "{}").delayed(2500));
        assertSafeBody(get("/api/v1/waiters/orders")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"))
                .andExpect(jsonPath("$.dependency").value("order")));
        get("/api/v1/waiters/tables/" + table.id() + "/orders").andExpect(status().isGatewayTimeout());
    }

    @Test
    void orderServerErrorOnDetailIsServiceUnavailableNotNotFound() throws Exception {
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}", request -> Response.json(502, "bad gateway"));
        assertSafeBody(get("/api/v1/waiters/orders/" + order.id())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void downstreamRejectingWaiterItselfIsABadGatewayNotAUserError(int rejectedStatus) throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/session-summaries", request -> Response.json(rejectedStatus, "{}"));
        assertSafeBody(get("/api/v1/waiters/tables")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_REJECTED")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not valid json",
            "[]",
            "{\"items\":[{\"tableSessionId\":\"00000000-0000-0000-0000-0000000000a1\"}]}",
            "{\"items\":[{\"tableSessionId\":\"00000000-0000-0000-0000-0000000000a1\",\"activeOrderCount\":-1}]}",
            "{\"items\":[{\"tableSessionId\":\"00000000-0000-0000-0000-0000000000ff\",\"activeOrderCount\":1}]}",
            "{\"items\":[{\"tableSessionId\":\"00000000-0000-0000-0000-0000000000a1\",\"activeOrderCount\":1},"
                    + "{\"tableSessionId\":\"00000000-0000-0000-0000-0000000000a1\",\"activeOrderCount\":1}]}",
            ""})
    void malformedOrderSummariesAreABadGatewayNotAnEmptyResult(String body) throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/session-summaries", request -> Response.json(200, body));
        assertSafeBody(get("/api/v1/waiters/tables")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"))
                .andExpect(jsonPath("$.dependency").value("order")));
    }

    @Test
    void wrongContentTypeFromOrderIsABadGateway() throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/session-summaries",
                request -> new Response(200, "text/plain", "ok", 0));
        assertSafeBody(get("/api/v1/waiters/tables").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE")));
    }

    @Test
    void orderContractViolationsAreABadGateway() throws Exception {
        Map<String, Object> broken = FakeBackends.orderJson(order);
        broken.remove("businessStatus");
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}", request -> Response.json(200, FakeBackends.write(broken)));
        assertSafeBody(get("/api/v1/waiters/orders/" + order.id()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE")));

        Map<String, Object> unknownStatus = FakeBackends.orderJson(order);
        unknownStatus.put("businessStatus", "SOMETHING_NEW");
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}", request -> Response.json(200, FakeBackends.write(unknownStatus)));
        get("/api/v1/waiters/orders/" + order.id()).andExpect(status().isBadGateway());

        Map<String, Object> servedButCancelled = FakeBackends.orderJson(order);
        servedButCancelled.put("businessStatus", "CANCELLED");
        servedButCancelled.put("deliveryStatus", "SERVED");
        servedButCancelled.put("servedAt", "2026-10-16T10:00:00Z");
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}", request -> Response.json(200, FakeBackends.write(servedButCancelled)));
        get("/api/v1/waiters/orders/" + order.id()).andExpect(status().isBadGateway());
    }

    @Test
    void qrContractViolationsAreABadGateway() throws Exception {
        Map<String, Object> noNumber = FakeBackends.tableJson(table);
        noNumber.remove("tableNumber");
        STUB.override("GET", "/api/v1/internal/tables/[0-9a-f-]{36}", request -> Response.json(200, FakeBackends.write(noNumber)));
        get("/api/v1/waiters/tables/" + table.id()).andExpect(status().isBadGateway());

        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, "{\"items\":[],\"page\":0,\"size\":200}"));
        get("/api/v1/waiters/tables").andExpect(status().isBadGateway());
    }

    @Test
    void qrServiceThatLeaksAnotherTenantsTableIsNeverRelayed() throws Exception {
        FakeBackends.Table foreign = new FakeBackends.Table(UUID.randomUUID(), OTHER_RESTAURANT, "SECRET-9", "OCCUPIED", UUID.randomUUID());
        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.tableJson(foreign)), "page", 0, "size", 200, "totalElements", 1))));
        get("/api/v1/waiters/tables").andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("SECRET-9"))));

        STUB.override("GET", "/api/v1/internal/tables/[0-9a-f-]{36}",
                request -> Response.json(200, FakeBackends.write(FakeBackends.tableJson(foreign))));
        get("/api/v1/waiters/tables/" + foreign.id()).andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("SECRET-9"))));
    }

    @Test
    void qrPagingInconsistenciesAreABadGateway() throws Exception {
        List<Map<String, Object>> two = List.of(FakeBackends.tableJson(table), FakeBackends.tableJson(table));
        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", two, "page", 0, "size", 200, "totalElements", 2))));
        get("/api/v1/waiters/tables").andExpect(status().isBadGateway());

        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.tableJson(table)), "page", 3, "size", 200, "totalElements", 1))));
        get("/api/v1/waiters/tables").andExpect(status().isBadGateway());

        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.tableJson(table)), "page", 0, "size", 200, "totalElements", 5))));
        get("/api/v1/waiters/tables").andExpect(status().isBadGateway());
    }

    @Test
    void tooManyTablesFailLoudlyInsteadOfFilteringATruncatedSet() throws Exception {
        STUB.override("GET", "/api/v1/internal/tables", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(), "page", 0, "size", 200, "totalElements", 501))));
        get("/api/v1/waiters/tables").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TABLE_SNAPSHOT_LIMIT_EXCEEDED"));
        get("/api/v1/waiters/orders").andExpect(status().isServiceUnavailable());
    }

    @Test
    void orderPageMetadataThatDoesNotMatchTheRequestIsABadGateway() throws Exception {
        STUB.override("POST", "/api/v1/internal/orders/search", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(order)), "page", 4, "size", 20, "totalElements", 1))));
        get("/api/v1/waiters/orders").andExpect(status().isBadGateway());
        STUB.override("POST", "/api/v1/internal/orders/search", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(order)), "page", 0, "size", 20, "totalElements", 0))));
        get("/api/v1/waiters/orders").andExpect(status().isBadGateway());
        // The same order twice in one page.
        STUB.override("POST", "/api/v1/internal/orders/search", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(order),
                        FakeBackends.orderJson(order)), "page", 0, "size", 20, "totalElements", 2))));
        get("/api/v1/waiters/orders").andExpect(status().isBadGateway());
    }

    @Test
    void orderOfATableUnknownToQrIsABadGatewayAndNeverShownWithoutItsTable() throws Exception {
        FakeBackends.Table ghost = new FakeBackends.Table(UUID.randomUUID(), RESTAURANT, "GHOST", "OCCUPIED", SESSION);
        FakeBackends.Order lost = new FakeBackends.Order(UUID.randomUUID(), RESTAURANT, ghost.id(), SESSION, "ORD-GHOST",
                "ACCEPTED", "PENDING", "READY", Instant.parse("2026-10-16T09:00:00Z"),
                Instant.parse("2026-10-16T09:10:00Z"), null);
        STUB.override("POST", "/api/v1/internal/orders/search", request -> Response.json(200, FakeBackends.write(Map.of(
                "items", List.of(FakeBackends.orderJson(lost)), "page", 0, "size", 20, "totalElements", 1))));
        get("/api/v1/waiters/orders").andExpect(status().isBadGateway());
        STUB.override("GET", "/api/v1/internal/orders/[0-9a-f-]{36}",
                request -> Response.json(200, FakeBackends.write(FakeBackends.orderJson(lost))));
        get("/api/v1/waiters/orders/" + lost.id()).andExpect(status().isBadGateway());
    }
}
