package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An order is bound to the restaurant/table/sessions the QR Service vouched for; the client has no say. */
class OrderSessionBindingApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final String B1 = DevFixtures.TOKEN_B1;

    private String order(String token) throws Exception {
        addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        return json.readTree(placeOrder(token, newKey(), confirmedCheckout(token)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("orderId").asString();
    }

    @Test
    void orderCarriesTheIdsResolvedByTheQrService() throws Exception {
        String orderId = order(A1);
        var row = jdbc.queryForMap("SELECT restaurant_id, table_id, table_session_id, guest_session_id, cart_id"
                + " FROM orders WHERE id = ?::uuid", orderId);
        assertEquals(DevFixtures.RESTAURANT_A, row.get("restaurant_id"));
        assertEquals(DevFixtures.TABLE_A1, row.get("table_id"));
        assertEquals(DevFixtures.TABLE_SESSION_A1, row.get("table_session_id"));
        assertEquals(DevFixtures.GUEST_A1, row.get("guest_session_id"));
        // and it is the very cart of that guest
        assertEquals(row.get("cart_id"), jdbc.queryForObject("SELECT id FROM carts WHERE guest_session_id = ?",
                UUID.class, DevFixtures.GUEST_A1));
    }

    @Test
    void severalGuestsAtOneTableGetSeparateOrdersOnTheSameTableSession() throws Exception {
        String first = order(A1);
        String second = order(A2);
        String third = order(A1); // the same guest may order again with a new cart

        assertNotEquals(first, second);
        assertNotEquals(first, third);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM orders WHERE table_session_id = ?",
                Integer.class, DevFixtures.TABLE_SESSION_A1));
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT guest_session_id) FROM orders", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(DISTINCT cart_id) FROM orders", Integer.class));
    }

    @Test
    void ordersOfDifferentTablesAndRestaurantsStaySeparate() throws Exception {
        order(A1);
        order(B1);
        assertEquals(DevFixtures.RESTAURANT_B, jdbc.queryForObject(
                "SELECT restaurant_id FROM orders WHERE guest_session_id = ?", UUID.class, DevFixtures.GUEST_B1));
        assertEquals(DevFixtures.TABLE_SESSION_B1, jdbc.queryForObject(
                "SELECT table_session_id FROM orders WHERE guest_session_id = ?", UUID.class, DevFixtures.GUEST_B1));

        // a guest of the same restaurant at another table is bound to that table's session
        UUID otherTable = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        qr.register("other-table", new GuestSessionContext(DevFixtures.RESTAURANT_A, otherTable, otherSession,
                UUID.randomUUID(), SessionState.ACTIVE));
        order("other-table");
        assertEquals(otherSession, jdbc.queryForObject("SELECT table_session_id FROM orders WHERE table_id = ?",
                UUID.class, otherTable));
    }

    @Test
    void aCartCanNeverBeOrderedByAnotherGuestOrRestaurant() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> a1Body = confirmedCheckout(A1);

        // A2 / B1 have no cart: they cannot order A1's cart even by replaying A1's confirmation
        placeOrder(A2, newKey(), a1Body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        placeOrder(B1, newKey(), a1Body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        assertEquals(0, count("orders"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }

    @Test
    void identityFieldsInTheBodyCannotRedirectTheOrder() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders")
                        .header("Authorization", "Bearer " + A1).header("Idempotency-Key", newKey())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_B.toString())
                        .contentType("application/json")
                        .content("{\"expectedCartVersion\": " + body.get("expectedCartVersion")
                                + ", \"expectedTotalMinor\": " + body.get("expectedTotalMinor")
                                + ", \"tableId\": \"" + DevFixtures.TABLE_B1 + "\"}"))
                .andExpect(status().isBadRequest());
        // the header is ignored; the order still lands in the QR-resolved restaurant
        placeOrder(A1, newKey(), body).andExpect(status().isCreated());
        assertEquals(List.of(DevFixtures.RESTAURANT_A), jdbc.queryForList("SELECT restaurant_id FROM orders", UUID.class));
    }
}
