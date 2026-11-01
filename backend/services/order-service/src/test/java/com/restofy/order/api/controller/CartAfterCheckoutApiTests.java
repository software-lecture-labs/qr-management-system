package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CHECKED_OUT is final: once a cart became an order, nothing in the API can change it, and a new cart starts fresh. */
class CartAfterCheckoutApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Test
    void aCheckedOutCartStaysExactlyAsOrderedAndTheGuestStartsANewOne() throws Exception {
        String orderedLine = addAndGetFirstItemId(A1, DevFixtures.SALAD, 2);
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());
        Map<String, Object> ordered = jdbc.queryForMap(
                "SELECT id, status, version, currency, updated_at FROM carts");
        int orderedLines = count("cart_items");

        // the ordered line can no longer be reached: the guest has no active cart
        patchItem(A1, orderedLine, Map.of("quantity", 5)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        removeItem(A1, orderedLine).andExpect(status().isNotFound());
        clearCart(A1).andExpect(status().isOk());
        getCart(A1).andExpect(status().isOk()).andExpect(jsonPath("$.cartId").isEmpty())
                .andExpect(jsonPath("$.items.length()").value(0));

        // a new addition opens another ACTIVE cart; the ordered one is not reused
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        assertEquals(2, count("carts"));
        assertEquals(ordered, jdbc.queryForMap("SELECT id, status, version, currency, updated_at FROM carts WHERE id = ?",
                ordered.get("id")), "the checked-out cart did not change at all");
        assertEquals(orderedLines + 1, count("cart_items"));
        assertEquals(1, count("orders"));
    }

    @Test
    void aSecondCheckoutOfTheSameCartVersionIsRefusedAndOrdersNothing() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        placeOrder(A1, newKey(), body).andExpect(status().isCreated());

        placeOrder(A1, newKey(), body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        assertEquals(1, count("orders"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM carts", String.class));
    }
}
