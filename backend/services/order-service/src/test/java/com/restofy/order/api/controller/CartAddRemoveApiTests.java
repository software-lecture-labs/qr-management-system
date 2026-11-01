package com.restofy.order.api.controller;

import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartAddRemoveApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final String B1 = DevFixtures.TOKEN_B1;

    @Test
    void addsProductWithServerSidePriceAndVat() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").isNotEmpty())
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.sessionActive").value(true))
                .andExpect(jsonPath("$.estimate").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Dev Burger"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(25_000))
                .andExpect(jsonPath("$.items[0].lineGrossMinor").value(50_000))
                // 50000 * 10000 / 11000 = 45454.54 -> 45455 (ROUND_HALF_UP)
                .andExpect(jsonPath("$.items[0].lineNetMinor").value(45_455))
                .andExpect(jsonPath("$.items[0].lineVatMinor").value(4_545))
                .andExpect(jsonPath("$.totals.grossMinor").value(50_000))
                .andExpect(jsonPath("$.totals.netMinor").value(45_455))
                .andExpect(jsonPath("$.totals.vatMinor").value(4_545))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void addingTheSameSelectionTwiceMergesIntoOneLine() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 2)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.BURGER, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.totals.grossMinor").value(125_000));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
    }

    @Test
    void differentProductsAreSeparateLines() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totals.grossMinor").value(34_000));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        addItem(A1, product(UUID.randomUUID(), 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void productOfAnotherRestaurantIsNotFound() throws Exception {
        // BURGER only exists in restaurant A; guest B1 belongs to restaurant B
        addItem(B1, product(DevFixtures.BURGER, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void unpublishedOrUnavailableProductsCannotBeAdded() throws Exception {
        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), true, false,
                burger.currency(), burger.basePriceGrossMinor(), burger.taxRateBps(), burger.options()));
        addItem(A1, product(DevFixtures.BURGER, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));

        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), burger.name(), false, true,
                burger.currency(), burger.basePriceGrossMinor(), burger.taxRateBps(), burger.options()));
        addItem(A1, product(DevFixtures.BURGER, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void clientSuppliedPriceOrIdentityFieldsAreRejected() throws Exception {
        for (String extraField : List.of("\"unitPrice\": 1", "\"price\": 1", "\"restaurantId\": \"" + UUID.randomUUID() + "\"",
                "\"guestSessionId\": \"" + UUID.randomUUID() + "\"", "\"taxRateBps\": 0")) {
            addItemRaw(A1, "{\"productId\": \"" + DevFixtures.BURGER + "\", \"quantity\": 1, " + extraField + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void invalidBodiesGetStructuredErrors() throws Exception {
        addItemRaw(A1, "{\"quantity\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("productId"));
        addItemRaw(A1, "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", not(containsString("Jackson"))));
        addItemRaw(A1, "{\"productId\": \"not-a-uuid\", \"quantity\": 1}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousAndInvalidCredentialsAreRejected() throws Exception {
        addItem(null, product(DevFixtures.BURGER, 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        addItem("unknown-token", product(DevFixtures.BURGER, 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post(CART + "/items").header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void removesOnlyTheRequestedLine() throws Exception {
        addItem(A1, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        String saladLine = json.readTree(addItem(A1, product(DevFixtures.SALAD, 2)).andReturn().getResponse()
                .getContentAsString()).path("items").get(1).path("cartItemId").asString();

        removeItem(A1, saladLine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(DevFixtures.BURGER.toString()))
                .andExpect(jsonPath("$.totals.grossMinor").value(25_000));
    }

    @Test
    void removingAnUnknownOrAlreadyRemovedLineIsNotFound() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 1);
        removeItem(A1, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        removeItem(A1, lineId).andExpect(status().isOk());
        removeItem(A1, lineId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void cannotRemoveAnotherGuestsLine() throws Exception {
        String otherGuestsLine = addAndGetFirstItemId(A2, DevFixtures.BURGER, 1);
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        removeItem(A1, otherGuestsLine)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
        getCart(A2).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void cannotRemoveALineOfAnotherRestaurant() throws Exception {
        String restaurantALine = addAndGetFirstItemId(A1, DevFixtures.BURGER, 1);
        addItem(B1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        removeItem(B1, restaurantALine).andExpect(status().isNotFound());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class));
    }

    @Test
    void removingFromAGuestWithoutACartIsNotFound() throws Exception {
        removeItem(A1, UUID.randomUUID()).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class));
    }

    @Test
    void closedOrExpiredSessionsCannotWrite() throws Exception {
        String lineId = addAndGetFirstItemId(A1, DevFixtures.BURGER, 1);

        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            addItem(A1, product(DevFixtures.SALAD, 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
            removeItem(A1, lineId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        }
        qr.register(A1, DevFixtures.guestA1(SessionState.ACTIVE));
        getCart(A1).andExpect(jsonPath("$.items", hasSize(1)));
    }
}
