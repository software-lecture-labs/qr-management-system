package com.restofy.waiter.api;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.DownstreamStub;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization boundary of the real filter chain and of the service layer. Authentication itself is
 * NOT IMPLEMENTED (Auth contract pending), so the production behaviour for every protected request is 401.
 */
class WaiterApiSecurityTests extends ApiTestBase {

    private static final String[] PROTECTED = {
            "/api/v1/waiters/tables",
            "/api/v1/waiters/tables/00000000-0000-0000-0000-000000000001",
            "/api/v1/waiters/tables/00000000-0000-0000-0000-000000000001/orders",
            "/api/v1/waiters/orders",
            "/api/v1/waiters/orders/00000000-0000-0000-0000-000000000001"
    };

    private void assertNoDownstreamCall() {
        assertTrue(STUB.requests().isEmpty(), "A rejected request must not reach QR or Order");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/waiters/tables",
            "/api/v1/waiters/tables/00000000-0000-0000-0000-000000000001",
            "/api/v1/waiters/tables/00000000-0000-0000-0000-000000000001/orders",
            "/api/v1/waiters/orders",
            "/api/v1/waiters/orders/00000000-0000-0000-0000-000000000001"})
    void anonymousRequestsAreRejectedWith401AndProblemDetail(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
        assertNoDownstreamCall();
    }

    @Test
    void forgedTenantAndRoleHeadersDoNotAuthenticateAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables")
                        .header("X-Restaurant-Id", RESTAURANT.toString())
                        .header("X-Role", "WAITER")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        assertNoDownstreamCall();
    }

    @ParameterizedTest
    @ValueSource(strings = {"KITCHEN", "ADMIN"})
    void otherStaffRolesAreForbiddenBeforeAnyDownstreamCall(String role) throws Exception {
        for (String path : PROTECTED) {
            mockMvc.perform(get(path).with(asStaff(StaffRole.valueOf(role), RESTAURANT)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertNoDownstreamCall();
    }

    @Test
    void principalOfTheWrongTypeIsForbiddenEvenWithTheWaiterAuthority() throws Exception {
        // E.g. a different authentication mechanism: the service never trusts an unverified identity shape.
        mockMvc.perform(get("/api/v1/waiters/tables").with(as("someone", "WAITER")))
                .andExpect(status().isForbidden());
        assertNoDownstreamCall();
    }

    @Test
    void serviceBoundaryRechecksTheRoleOfThePrincipalNotJustTheAuthority() throws Exception {
        RequestPostProcessor kitchenPrincipalWithWaiterAuthority =
                as(new AuthenticatedStaff(UUID.randomUUID(), RESTAURANT, StaffRole.KITCHEN), "WAITER");
        mockMvc.perform(get("/api/v1/waiters/tables").with(kitchenPrincipalWithWaiterAuthority))
                .andExpect(status().isForbidden());
        RequestPostProcessor waiterPrincipalWithKitchenAuthority =
                as(new AuthenticatedStaff(UUID.randomUUID(), RESTAURANT, StaffRole.WAITER), "KITCHEN");
        mockMvc.perform(get("/api/v1/waiters/tables").with(waiterPrincipalWithKitchenAuthority))
                .andExpect(status().isForbidden());
        assertNoDownstreamCall();
    }

    @Test
    void restaurantComesOnlyFromTheVerifiedPrincipalNeverFromHeaders() throws Exception {
        addTable("1", "AVAILABLE", null);
        addTable(OTHER_RESTAURANT, "9", "AVAILABLE", null);
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter())
                        .header("X-Restaurant-Id", OTHER_RESTAURANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].tableNumber").value("1"));
        for (DownstreamStub.Request request : STUB.requests()) {
            assertTrue(!request.rawQuery().contains(OTHER_RESTAURANT.toString()) && !request.body().contains(OTHER_RESTAURANT.toString()),
                    "The forged restaurant must never be forwarded");
        }
        assertTrue(STUB.requests().stream().anyMatch(r -> r.query().containsKey("restaurantId")
                && RESTAURANT.toString().equals(r.query().get("restaurantId"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurantId", "role", "sort", "tenant"})
    void unknownQueryParametersAreRejectedSoAClientCannotSelectATenantInTheUrl(String name) throws Exception {
        for (String path : PROTECTED) {
            mockMvc.perform(get(path).with(asWaiter()).param(name, OTHER_RESTAURANT.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertNoDownstreamCall();
    }

    @Test
    void successfulResponsesAreNotCacheable() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/tables").with(asWaiter()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    }

    @Test
    void everythingOutsideTheWaiterApiAndHealthIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/other")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/other").with(asWaiter())).andExpect(status().isForbidden());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        assertNoDownstreamCall();
    }

    @Test
    void healthProbesStayOpenWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void unknownPathsUnderTheWaiterApiAnswerWithProblemDetailNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/waiters/unknown").with(asWaiter()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void mutatingMethodsAreNotSupportedAndStayProtected() throws Exception {
        mockMvc.perform(post("/api/v1/waiters/tables").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/waiters/tables").with(asWaiter()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        assertNoDownstreamCall();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM service_requests", Integer.class));
    }
}
