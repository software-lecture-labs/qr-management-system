package com.restofy.order.api.controller;

import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** K-04: KITCHEN reads its own restaurant's orders in a reduced view, and nothing else. */
class KitchenReadAccessApiTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String KITCHEN = staff(DevFixtures.STAFF_KITCHEN_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);

    /** A burger with a removed ingredient, two cheese extras and a note: everything the kitchen needs to see. */
    private UUID customisedOrder() throws Exception {
        addItem(A1, Map.of("productId", DevFixtures.BURGER, "quantity", 2,
                "removedIngredientIds", List.of(DevFixtures.OPT_ONION),
                "extras", List.of(extra(DevFixtures.OPT_CHEESE, 2)), "note", "well done")).andExpect(status().isOk());
        String body = placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("orderId").asString());
    }

    @Test
    void theKitchenSeesWhatToPrepareAndNothingAboutMoneyOrPayment() throws Exception {
        UUID orderId = customisedOrder();
        String raw = getWith(KITCHEN, "/api/v1/orders/" + orderId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = json.readTree(raw);

        assertEquals("RECEIVED", order.path("fulfillmentStatus").asString());
        assertEquals(DevFixtures.TABLE_A1.toString(), order.path("tableId").asString());
        JsonNode line = order.path("items").get(0);
        assertEquals(2, line.path("quantity").asInt());
        assertEquals("Dev Burger", line.path("name").asString());
        assertEquals("well done", line.path("note").asString());
        assertEquals("Onion", line.path("removedIngredients").get(0).path("name").asString());
        assertEquals("Extra cheese", line.path("extras").get(0).path("name").asString());
        assertEquals(2, line.path("extras").get(0).path("quantity").asInt());

        for (String hidden : new String[]{"paymentStatus", "amounts", "currentDueMinor", "currency", "unitGrossMinor",
                "lineGrossMinor", "lineNetMinor", "lineVatMinor", "taxRateBps", "unitPriceMinor", "grossMinor",
                "netMinor", "guestSessionId", "cartId"}) {
            assertFalse(raw.contains("\"" + hidden + "\""), "the kitchen view must not carry " + hidden);
        }
    }

    @Test
    void everyOtherReaderStillGetsThePricesAndPayment() throws Exception {
        UUID orderId = customisedOrder();
        for (String reader : new String[]{ADMIN, staff(DevFixtures.STAFF_WAITER_A), guest(A1)}) {
            getWith(reader, "/api/v1/orders/" + orderId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentStatus").value("NOT_REQUESTED"))
                    .andExpect(jsonPath("$.currency").value("TRY"))
                    .andExpect(jsonPath("$.amounts.grossMinor").value(60_000))
                    .andExpect(jsonPath("$.currentDueMinor").isNumber())
                    .andExpect(jsonPath("$.items[0].unitGrossMinor").isNumber())
                    .andExpect(jsonPath("$.items[0].extras[0].unitPriceMinor").isNumber());
        }
    }

    @Test
    void theKitchenListIsReducedFilteredByViewAndCannotFilterByPayment() throws Exception {
        UUID first = customisedOrder();
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        JsonNode page = body(getWith(KITCHEN, "/api/v1/orders?view=active"));
        assertEquals(2, page.path("totalItems").asInt());
        JsonNode summary = page.path("items").get(0);
        assertEquals("RECEIVED", summary.path("fulfillmentStatus").asString());
        assertTrue(summary.path("unitCount").asLong() > 0);
        String raw = page.toString();
        for (String hidden : new String[]{"paymentStatus", "grossMinor", "currentDueMinor", "currency"}) {
            assertFalse(raw.contains("\"" + hidden + "\""), hidden);
        }

        // the kitchen follows the preparation: new, preparing, ready; the cancelled view is open to it as well
        advanceTo(first, "PREPARING");
        Set<String> statuses = new HashSet<>();
        body(getWith(KITCHEN, "/api/v1/orders?view=active")).path("items")
                .forEach(item -> statuses.add(item.path("fulfillmentStatus").asString()));
        assertEquals(Set.of("PREPARING", "RECEIVED"), statuses);
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", second);
        assertEquals(1, body(getWith(KITCHEN, "/api/v1/orders?view=cancelled")).path("totalItems").asInt());

        // no payment filter: that would reveal payment information through the back door
        getWith(KITCHEN, "/api/v1/orders?paymentStatus=PAID").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getWith(KITCHEN, "/api/v1/orders?paymentStatus=NOT_REQUESTED").andExpect(status().isForbidden());
    }

    @Test
    void theKitchenIsConfinedToItsOwnRestaurantAndCannotReadTheHistory() throws Exception {
        UUID orderId = customisedOrder();
        UUID otherRestaurant = placeSaladOrder(DevFixtures.TOKEN_B1);
        getWith(KITCHEN, "/api/v1/orders/" + otherRestaurant).andExpect(status().isNotFound());
        assertEquals(1, body(getWith(KITCHEN, "/api/v1/orders")).path("totalItems").asInt());
        auth.register("kitchen-b", new StaffActor(UUID.randomUUID(), DevFixtures.RESTAURANT_B, StaffRole.KITCHEN));
        assertEquals(1, body(getWith(staff("kitchen-b"), "/api/v1/orders")).path("totalItems").asInt());
        getWith(staff("kitchen-b"), "/api/v1/orders/" + orderId).andExpect(status().isNotFound());

        // the history carries the payment axis and staff identifiers
        getWith(KITCHEN, "/api/v1/orders/" + orderId + "/history").andExpect(status().isForbidden());
        // a table-scoped query of another restaurant's table session finds nothing, it does not error or leak
        assertEquals(0, body(getWith(KITCHEN, "/api/v1/orders?tableSessionId=" + DevFixtures.TABLE_SESSION_B1))
                .path("totalItems").asInt());
    }

    @Test
    void readingGivesTheKitchenNoWriteAuthorityOverMoneyOrOrders() throws Exception {
        UUID orderId = customisedOrder();
        // it cannot set what is not its own, create, or touch payment
        patchStatus(KITCHEN, orderId, "SERVED").andExpect(status().isForbidden());
        patchStatus(KITCHEN, orderId, "COMPLETED").andExpect(status().isForbidden());
        patchStatus(KITCHEN, orderId, "CANCELLED").andExpect(status().isConflict());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders")
                        .header("Authorization", KITCHEN).contentType("application/json")
                        .content("{\"expectedCartVersion\":1,\"expectedTotalMinor\":1}"))
                .andExpect(status().isForbidden());
        paymentEvent(KITCHEN, paymentBody(UUID.randomUUID(), orderId, "PAID", 1L, "TRY", Instant.now()))
                .andExpect(status().isForbidden());
        assertEquals("NOT_REQUESTED", paymentOf(orderId));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }
}
