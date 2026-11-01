package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 1: one order's detail, who may see it, and that the price snapshot never moves. */
class OrderDetailApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String A2 = DevFixtures.TOKEN_A2;
    private static final String B1 = DevFixtures.TOKEN_B1;

    private UUID customizedOrder(String token) throws Exception {
        addItem(token, product(DevFixtures.BURGER, 2, List.of(DevFixtures.OPT_ONION),
                List.of(extra(DevFixtures.OPT_CHEESE, 1)))).andExpect(status().isOk());
        addItem(token, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        String body = placeOrder(token, newKey(), confirmedCheckout(token)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("orderId").asString());
    }

    @Test
    void theOwnerSeesTheFullOrderWithItsSnapshotAndBothStatuses() throws Exception {
        UUID orderId = customizedOrder(A1);
        getWith(guest(A1), "/api/v1/orders/" + orderId)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.orderNo").value("ORD-000001"))
                .andExpect(jsonPath("$.tableId").value(DevFixtures.TABLE_A1.toString()))
                .andExpect(jsonPath("$.tableSessionId").value(DevFixtures.TABLE_SESSION_A1.toString()))
                .andExpect(jsonPath("$.fulfillmentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.amounts.grossMinor").value(64_000))
                .andExpect(jsonPath("$.currentDueMinor").value(64_000))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name").value("Dev Burger"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitGrossMinor").value(27_500))
                .andExpect(jsonPath("$.items[0].taxRateBps").value(1_000))
                .andExpect(jsonPath("$.items[0].removedIngredients[0].name").value("Onion"))
                .andExpect(jsonPath("$.items[0].extras[0].name").value("Extra cheese"))
                .andExpect(jsonPath("$.items[0].extras[0].unitPriceMinor").value(2_500))
                .andExpect(jsonPath("$.placedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void theDetailDoesNotExposeInternalIdentifiersOfTheGuestOrTheCart() throws Exception {
        UUID orderId = customizedOrder(A1);
        String body = getWith(guest(A1), "/api/v1/orders/" + orderId).andReturn().getResponse().getContentAsString();
        for (String hidden : new String[]{"guestSessionId", "cartId", DevFixtures.GUEST_A1.toString(), "restaurantId"}) {
            assertEquals(false, body.contains(hidden), "must not expose " + hidden);
        }
    }

    @Test
    void laterMenuChangesNeverChangeTheOrderThatIsShown() throws Exception {
        UUID orderId = customizedOrder(A1);
        String before = getWith(guest(A1), "/api/v1/orders/" + orderId).andReturn().getResponse().getContentAsString();

        ProductSnapshot burger = DevFixtures.burgerSnapshot();
        menu.put(DevFixtures.RESTAURANT_A, new ProductSnapshot(burger.productId(), "Renamed", true, true, "TRY",
                99_900L, 2_000, burger.options()));
        menu.remove(DevFixtures.RESTAURANT_A, DevFixtures.SALAD);

        JsonNode after = json.readTree(getWith(guest(A1), "/api/v1/orders/" + orderId).andReturn().getResponse()
                .getContentAsString());
        assertEquals(json.readTree(before), after);
        assertEquals("Dev Burger", after.path("items").get(0).path("name").asString());
    }

    @Test
    void otherGuestsAndOtherRestaurantsGetTheSame404AsAnUnknownId() throws Exception {
        UUID orderId = customizedOrder(A1);
        // same table, other guest
        getWith(guest(A2), "/api/v1/orders/" + orderId)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        // other restaurant
        getWith(guest(B1), "/api/v1/orders/" + orderId)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        // unknown id
        getWith(guest(A1), "/api/v1/orders/" + UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void knowingOrIdentityHintsDoNotHelp() throws Exception {
        UUID orderId = customizedOrder(A1);
        mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/orders/" + orderId).param("guestSessionId", DevFixtures.GUEST_A1.toString())
                        .header("Authorization", guest(A2)).header("X-Guest-Session-Id", DevFixtures.GUEST_A1.toString())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousAndMalformedRequestsAreRejected() throws Exception {
        UUID orderId = customizedOrder(A1);
        getWith(null, "/api/v1/orders/" + orderId).andExpect(status().isUnauthorized());
        getWith(guest(A1), "/api/v1/orders/not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void aGuestWhoseSessionEndedCanNoLongerReadOrders() throws Exception {
        UUID orderId = customizedOrder(A1);
        for (SessionState dead : List.of(SessionState.CLOSED, SessionState.EXPIRED)) {
            qr.register(A1, DevFixtures.guestA1(dead));
            getWith(guest(A1), "/api/v1/orders/" + orderId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"))
                    .andExpect(jsonPath("$.orderNo").doesNotExist());
        }
        qr.register(A1, DevFixtures.guestA1(SessionState.ACTIVE));
        getWith(guest(A1), "/api/v1/orders/" + orderId).andExpect(status().isOk());
    }

    // ---- staff ----------------------------------------------------------------------------------------------------

    @Test
    void adminAndWaiterOfTheSameRestaurantMaySeeAnyOfItsOrders() throws Exception {
        UUID orderId = customizedOrder(A1);
        for (String credential : List.of(DevFixtures.STAFF_ADMIN_A, DevFixtures.STAFF_WAITER_A)) {
            getWith(staff(credential), "/api/v1/orders/" + orderId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.items", hasSize(2)));
        }
    }

    @Test
    void staffOfAnotherRestaurantSeesNothingEvenWithTheRightOrderId() throws Exception {
        UUID orderId = customizedOrder(A1);
        for (String credential : List.of(DevFixtures.STAFF_ADMIN_B, DevFixtures.STAFF_WAITER_B)) {
            getWith(staff(credential), "/api/v1/orders/" + orderId)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }
        // and their own restaurant's order is visible to them
        addItem(B1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        UUID ownOrder = UUID.fromString(json.readTree(placeOrder(B1, newKey(), confirmedCheckout(B1))
                .andReturn().getResponse().getContentAsString()).path("orderId").asString());
        getWith(staff(DevFixtures.STAFF_ADMIN_B), "/api/v1/orders/" + ownOrder).andExpect(status().isOk());
        getWith(staff(DevFixtures.STAFF_ADMIN_A), "/api/v1/orders/" + ownOrder).andExpect(status().isNotFound());
    }

    @Test
    void kitchenStaffReadOnlyTheirOwnRestaurantsOrdersAndAnotherRestaurantsOrderIsMissing() throws Exception {
        UUID orderId = customizedOrder(A1);
        getWith(staff(DevFixtures.STAFF_KITCHEN_A), "/api/v1/orders/" + orderId).andExpect(status().isOk());
        getWith(staff(DevFixtures.STAFF_KITCHEN_A), "/api/v1/orders/" + UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        auth.register("kitchen-of-b", new StaffActor(UUID.randomUUID(),
                DevFixtures.RESTAURANT_B, StaffRole.KITCHEN));
        getWith(staff("kitchen-of-b"), "/api/v1/orders/" + orderId).andExpect(status().isNotFound());
    }

    @Test
    void aRoleChangeAtTheAuthServiceTakesEffectOnTheNextRequest() throws Exception {
        UUID orderId = customizedOrder(A1);
        getWith(staff(DevFixtures.STAFF_WAITER_A), "/api/v1/orders/" + orderId).andExpect(status().isOk());
        auth.register(DevFixtures.STAFF_WAITER_A, new StaffActor(
                DevFixtures.USER_WAITER_A, DevFixtures.RESTAURANT_A, StaffRole.KITCHEN));
        // the same credential, now a KITCHEN role at the Auth Service: the reduced view, no payment or amounts
        getWith(staff(DevFixtures.STAFF_WAITER_A), "/api/v1/orders/" + orderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").doesNotExist());
        auth.unregister(DevFixtures.STAFF_WAITER_A);
        getWith(staff(DevFixtures.STAFF_WAITER_A), "/api/v1/orders/" + orderId).andExpect(status().isUnauthorized());
    }
}
