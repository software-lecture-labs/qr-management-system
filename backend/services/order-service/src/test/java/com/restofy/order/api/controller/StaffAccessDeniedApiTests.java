package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * There is no staff authentication in the Order Service yet (Auth Service integration is pending), so every staff
 * and service-to-service path must stay closed. Nothing a client sends - role headers, user ids, a JWT-looking bearer
 * token, a guest token - may open them. ORDER-03/04 will add staff access only through a real, verified identity.
 */
class StaffAccessDeniedApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final UUID SOME_ID = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    /** The paths the plan reserves for waiter / kitchen / admin / payment (none of them exists yet). */
    private static final List<String[]> STAFF_ROUTES = List.of(
            new String[]{"GET", "/api/v1/orders"},
            new String[]{"GET", "/api/v1/orders/" + SOME_ID},
            new String[]{"GET", "/api/v1/orders/" + SOME_ID + "/history"},
            new String[]{"PATCH", "/api/v1/orders/" + SOME_ID + "/fulfillment-status"},
            new String[]{"POST", "/api/v1/orders/" + SOME_ID + "/cancellations"},
            new String[]{"GET", "/api/v1/orders/" + SOME_ID + "/cancellations"},
            new String[]{"PATCH", "/api/v1/orders/" + SOME_ID + "/cancellations/" + SOME_ID + "/reason"},
            new String[]{"POST", "/api/v1/internal/order-events/kitchen"},
            new String[]{"POST", "/api/v1/internal/order-events/payment"},
            new String[]{"POST", "/api/v1/internal/order-events/kitchen-cancellation"},
            new String[]{"POST", "/api/v1/internal/order-events/kitchen-cancellation-request"},
            new String[]{"GET", "/api/v1/admin/order-cancellations"});

    private MockHttpServletRequestBuilder call(String[] route) {
        return request(HttpMethod.valueOf(route[0]), route[1]).contentType("application/json").content("{}");
    }

    @Test
    void forgedStaffIdentitiesOpenNothing() throws Exception {
        String fakeJwt = "eyJhbGciOiJub25lIn0.eyJyb2xlIjoiQURNSU4iLCJyZXN0YXVyYW50SWQiOiJhIn0.";
        for (String[] route : STAFF_ROUTES) {
            String label = route[0] + " " + route[1];
            mvc.perform(call(route)).andExpect(status().isUnauthorized());
            mvc.perform(call(route).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID().toString())
                            .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()))
                    .andExpect(result -> assertEquals(401, result.getResponse().getStatus(), label));
            mvc.perform(call(route).header("Authorization", "Bearer " + fakeJwt))
                    .andExpect(result -> assertEquals(401, result.getResponse().getStatus(), label));
            mvc.perform(call(route).header("Authorization", "Basic YWRtaW46YWRtaW4="))
                    .andExpect(result -> assertEquals(401, result.getResponse().getStatus(), label));
        }
    }

    /**
     * GET orders (list, one order, its history) are open to the own guest and to staff since ORDER-03 tasks 1 and 2;
     * POST .../cancellations is open to the own guest and to waiter / admin staff since ORDER-04.
     */
    private static boolean guestMayCall(String[] route) {
        return (route[0].equals("GET") && route[1].matches("/api/v1/orders(/[0-9a-f-]{36}(/history)?)?"))
                || (route[0].equals("POST") && route[1].matches("/api/v1/orders/[0-9a-f-]{36}/cancellations"))
                || (route[0].equals("GET") && route[1].matches("/api/v1/orders/[0-9a-f-]{36}/cancellations"));
    }

    @Test
    void aGuestSessionIsNeverStaffAuthority() throws Exception {
        for (String[] route : STAFF_ROUTES) {
            boolean orderPlacement = route[0].equals("POST") && route[1].equals("/api/v1/orders");
            if (orderPlacement || guestMayCall(route)) {
                continue;
            }
            mvc.perform(call(route).header("Authorization", "Bearer " + A1).header("X-Role", "ADMIN"))
                    .andExpect(result -> assertEquals(403, result.getResponse().getStatus(), route[0] + " " + route[1]))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    void guestsMayOnlyPlaceAndReadTheirOwnOrders() throws Exception {
        // reading an unknown order reaches the service and is a plain 404 (no existence leak), not 401/403
        mvc.perform(request(HttpMethod.GET, "/api/v1/orders/" + SOME_ID).header("Authorization", "Bearer " + A1))
                .andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.GET, "/api/v1/orders/" + SOME_ID + "/history")
                        .header("Authorization", "Bearer " + A1))
                .andExpect(status().isNotFound());
        // POST /api/v1/orders with a guest session reaches the controller: a validation error, not 401/403
        mvc.perform(request(HttpMethod.POST, "/api/v1/orders").header("Authorization", "Bearer " + A1)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableContent());
        // the same path with other methods is closed
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            mvc.perform(request(method, "/api/v1/orders").header("Authorization", "Bearer " + A1)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
}
