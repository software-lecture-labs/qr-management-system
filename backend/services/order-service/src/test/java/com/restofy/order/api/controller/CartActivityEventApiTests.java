package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001 K6: a successful cart change is announced to the QR Service in the same transaction, so it refreshes the
 * guest's 15-minute idle clock. Reading a cart, a replay and any refused change announce nothing.
 */
class CartActivityEventApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    private List<String> activities() {
        return jdbc.queryForList("SELECT payload->>'activity' FROM outbox_events WHERE event_type = 'CartActivityRecorded'"
                + " ORDER BY aggregate_version", String.class);
    }

    private JsonNode lastEvent() throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events"
                + " WHERE event_type = 'CartActivityRecorded' ORDER BY aggregate_version DESC LIMIT 1", String.class));
    }

    @Test
    void addingAnItemAnnouncesTheActivityToTheQrServiceOnly() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        assertEquals(List.of("ITEM_ADDED"), activities());
        JsonNode event = lastEvent();
        assertEquals("CartActivityRecorded", event.path("eventType").asString());
        assertEquals(1, event.path("schemaVersion").asInt());
        assertEquals(DevFixtures.RESTAURANT_A.toString(), event.path("restaurantId").asString());
        assertEquals(DevFixtures.TABLE_SESSION_A1.toString(), event.path("tableSessionId").asString());
        assertEquals(DevFixtures.GUEST_A1.toString(), event.path("guestSessionId").asString());
        assertEquals(jdbc.queryForObject("SELECT id::text FROM carts", String.class), event.path("cartId").asString());
        assertEquals(List.of("QR"), jdbc.queryForList("SELECT d.destination FROM outbox_deliveries d JOIN outbox_events e"
                + " ON e.id = d.event_id WHERE e.event_type = 'CartActivityRecorded'", String.class));
        assertEquals("CART", jdbc.queryForObject("SELECT aggregate_type FROM outbox_events"
                + " WHERE event_type = 'CartActivityRecorded'", String.class));
    }

    @Test
    void theEventCarriesNoProductPriceNoteOrCredential() throws Exception {
        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "note", "no onions please"))
                .andExpect(status().isOk());

        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events"
                + " WHERE event_type = 'CartActivityRecorded'", String.class).toLowerCase();
        for (String forbidden : new String[]{"token", "bearer", "authorization", "productid", DevFixtures.SALAD.toString(),
                "note", "onions", "price", "amount", "name"}) {
            assertFalse(payload.contains(forbidden.toLowerCase()), forbidden + " in " + payload);
        }
    }

    @Test
    void everyKindOfSuccessfulChangeIsAnnouncedOnce() throws Exception {
        String itemId = addAndGetFirstItemId(A1, DevFixtures.SALAD, 1);
        patchItem(A1, itemId, Map.of("quantity", 3)).andExpect(status().isOk());
        removeItem(A1, itemId).andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 2)).andExpect(status().isOk());
        clearCart(A1).andExpect(status().isOk());

        assertEquals(List.of("ITEM_ADDED", "ITEM_QUANTITY_CHANGED", "ITEM_REMOVED", "ITEM_ADDED", "CART_CLEARED"),
                activities());
    }

    @Test
    void changesThatChangeNothingAnnounceNothing() throws Exception {
        String itemId = addAndGetFirstItemId(A1, DevFixtures.SALAD, 2);
        assertEquals(1, countCartActivityEvents());

        patchItem(A1, itemId, Map.of("quantity", 2)).andExpect(status().isOk());   // same quantity
        getCart(A1).andExpect(status().isOk());                                    // reading is not activity
        clearCart(A1).andExpect(status().isOk());                                  // a real clear
        clearCart(A1).andExpect(status().isOk());                                  // already empty

        assertEquals(List.of("ITEM_ADDED", "CART_CLEARED"), activities());
    }

    @Test
    void anIdempotentReplayAnnouncesNothingNew() throws Exception {
        String key = newKey();
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(authorized(post(CART + "/items"), A1).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(toJson(product(DevFixtures.SALAD, 1))))
                    .andExpect(status().isOk());
        }

        assertEquals(List.of("ITEM_ADDED"), activities());
    }

    @Test
    void aRefusedChangeAnnouncesNothing() throws Exception {
        addItem(A1, product(UUID.randomUUID(), 1)).andExpect(status().isNotFound());       // unknown product
        addItem(A1, product(DevFixtures.SALAD, 0)).andExpect(status().is4xxClientError()); // invalid quantity
        addItem(null, product(DevFixtures.SALAD, 1)).andExpect(status().isUnauthorized());  // not authenticated
        addItem(A1, Map.of("productId", DevFixtures.SALAD, "quantity", 1, "expectedCartVersion", 99))
                .andExpect(status().isConflict());                                         // stale cart version
        removeItem(A1, UUID.randomUUID()).andExpect(status().isNotFound());                // unknown line

        assertEquals(0, countCartActivityEvents());
    }

    @Test
    void anEndedSessionCannotAnnounceActivity() throws Exception {
        qr.unregister(A1);

        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isUnauthorized());

        assertEquals(0, countCartActivityEvents());
    }

    @Test
    void eachGuestAnnouncesTheirOwnActivity() throws Exception {
        addItem(DevFixtures.TOKEN_A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        assertEquals(List.of(DevFixtures.GUEST_A1.toString(), DevFixtures.GUEST_A2.toString()),
                jdbc.queryForList("SELECT payload->>'guestSessionId' FROM outbox_events"
                        + " WHERE event_type = 'CartActivityRecorded' ORDER BY payload->>'guestSessionId'", String.class));
    }

    @Test
    void checkoutIsAnOrderEventNotACartActivity() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), confirmedCheckout(A1)).andExpect(status().isCreated());

        assertEquals(1, countCartActivityEvents());
        assertEquals(1, count("outbox_events"), "the OrderPlaced event");
    }
}
