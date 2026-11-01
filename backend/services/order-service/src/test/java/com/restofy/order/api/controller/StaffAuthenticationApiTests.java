package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff authentication through the AuthContextPort (MOCK in tests): separate from guests, never from headers. */
@ExtendWith(OutputCaptureExtension.class)
class StaffAuthenticationApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Test
    void aVerifiedStaffMemberIsAuthenticatedThroughTheStaffScheme() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        getWith(staff(DevFixtures.STAFF_WAITER_A), "/api/v1/orders/" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void unknownEmptyOrOversizedStaffCredentialsAreRejected() throws Exception {
        String path = "/api/v1/orders/" + UUID.randomUUID();
        getWith(staff("not-a-known-credential"), path)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        getWith("Staff ", path).andExpect(status().isUnauthorized());
        getWith(staff("x".repeat(600)), path).andExpect(status().isUnauthorized());
    }

    @Test
    void guestAndStaffCredentialsAreNotInterchangeable() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String path = "/api/v1/orders/" + orderId;
        // a guest token under the Staff scheme and a staff credential under Bearer are both unknown to the verifier
        getWith(staff(A1), path).andExpect(status().isUnauthorized());
        getWith(guest(DevFixtures.STAFF_ADMIN_A), path).andExpect(status().isUnauthorized());
    }

    @Test
    void roleAndRestaurantHeadersNeverCreateOrChangeAStaffIdentity() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String path = "/api/v1/orders/" + orderId;
        mvc.perform(get(path).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()))
                .andExpect(status().isUnauthorized());
        // a real restaurant-B staff credential with restaurant-A headers is still restaurant B
        mvc.perform(get(path).header("Authorization", staff(DevFixtures.STAFF_ADMIN_B))
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()).header("X-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffCannotUseTheGuestOnlyEndpoints() throws Exception {
        mvc.perform(get("/api/v1/carts/current").header("Authorization", staff(DevFixtures.STAFF_ADMIN_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v1/orders").header("Authorization", staff(DevFixtures.STAFF_ADMIN_A))
                        .header("Idempotency-Key", newKey()).contentType("application/json")
                        .content("{\"expectedCartVersion\": 0, \"expectedTotalMinor\": 0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCredentialsAreNeverLogged(CapturedOutput output) throws Exception {
        placeSaladOrder(A1);
        getWith(staff("secret-unknown-credential-123"), "/api/v1/orders/" + UUID.randomUUID())
                .andExpect(status().isUnauthorized());
        getWith(staff(DevFixtures.STAFF_ADMIN_A), "/api/v1/orders/" + UUID.randomUUID())
                .andExpect(status().isNotFound());
        String logs = output.getAll();
        for (String secret : new String[]{"secret-unknown-credential-123", DevFixtures.STAFF_ADMIN_A,
                DevFixtures.STAFF_WAITER_A, "Staff dev-mock"}) {
            assertFalse(logs.contains(secret), "logs must not contain: " + secret);
        }
    }
}
