package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Restaurant / table / guest isolation and API-surface hardening, end to end over HTTP + PostgreSQL.
 * The OpenAPI flags are pinned to the production default so a developer's local SWAGGER_ENABLED=true cannot change
 * what this test asserts.
 */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class CartIsolationApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final String B1 = DevFixtures.TOKEN_B1;

    @Test
    void guestsAtTheSameTableHaveSeparateCartsBoundToTheSameTableSession() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A2, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());

        getCart(A1).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(DevFixtures.BURGER.toString()));
        getCart(A2).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(DevFixtures.SALAD.toString()));

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM carts WHERE table_session_id = ?", Integer.class,
                DevFixtures.TABLE_SESSION_A1));
    }

    @Test
    void anotherRestaurantNeverSeesOrTouchesTheCart() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        getCart(B1).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        clearCart(B1).andExpect(status().isOk());
        getCart(A1).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void cartRowsCarryTheIdsResolvedByTheQrServiceNotAnythingFromTheRequest() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        var row = jdbc.queryForMap("SELECT restaurant_id, table_id, table_session_id, guest_session_id FROM carts");
        assertEquals(DevFixtures.RESTAURANT_A, row.get("restaurant_id"));
        assertEquals(DevFixtures.TABLE_A1, row.get("table_id"));
        assertEquals(DevFixtures.TABLE_SESSION_A1, row.get("table_session_id"));
        assertEquals(DevFixtures.GUEST_A1, row.get("guest_session_id"));
    }

    @Test
    void identityHintsInQueryOrHeadersAreIgnored() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());

        // B1 pretends to be restaurant A / guest A1 through every channel a client could try
        mvc.perform(authorized(get(CART)
                        .param("restaurantId", DevFixtures.RESTAURANT_A.toString())
                        .param("guestSessionId", DevFixtures.GUEST_A1.toString())
                        .param("tableSessionId", DevFixtures.TABLE_SESSION_A1.toString())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString())
                        .header("X-Guest-Session-Id", DevFixtures.GUEST_A1.toString())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Role", "ADMIN"), B1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void cartsCannotBeAddressedByIdAndOtherApiAreasAreClosed() throws Exception {
        String cartId = UUID.randomUUID().toString();
        // authenticated guest: no such route
        mvc.perform(authorized(get("/api/v1/carts/" + cartId), A1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // anonymous: not even told whether the route exists
        mvc.perform(get("/api/v1/carts/" + cartId)).andExpect(status().isUnauthorized());
        // everything outside the cart API is deny-by-default
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(authorized(get("/api/v1/admin/order-cancellations"), A1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthProbeIsPublicButOpenApiIsNotExposedByDefault() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodsAndMediaTypesGetTheStandardErrorBody() throws Exception {
        mvc.perform(authorized(put(CART + "/items"), A1))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(authorized(post(CART + "/items"), A1).contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(authorized(get(CART + "/items/not-a-uuid"), A1)).andExpect(status().isMethodNotAllowed());
        mvc.perform(authorized(MockMvcRequestBuilders
                        .delete(CART + "/items/not-a-uuid"), A1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void oversizedAndMalformedCredentialsAreRejectedBeforeTheQrServiceIsAsked() throws Exception {
        mvc.perform(get(CART).header("Authorization", "Bearer " + "x".repeat(513)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CART).header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CART).header("Authorization", A1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyResponseCarriesACorrelationIdAndOnlyWellFormedOnesAreEchoed() throws Exception {
        mvc.perform(get(CART).header("X-Correlation-Id", "client-trace-42"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "client-trace-42"))
                .andExpect(jsonPath("$.correlationId").value("client-trace-42"));
        mvc.perform(get(CART).header("X-Correlation-Id", "bad value with spaces & <script>"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id",
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
        getCart(A1).andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void errorBodiesNeverLeakInternals() throws Exception {
        String body = addItemRaw(A1, "{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": \"x\"}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        for (String forbidden : new String[]{"Exception", "com.restofy", "jackson", "at java", "SQL", "jdbc"}) {
            assertEquals(false, body.contains(forbidden), "error body must not contain '" + forbidden + "': " + body);
        }
    }
}
