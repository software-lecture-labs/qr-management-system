package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OrderPlaced outbox event: contract content, per-destination delivery rows and what it must not contain. */
class OrderPlacedEventApiTests extends AbstractCartApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    private JsonNode placeCustomizedOrder(String correlationId) throws Exception {
        addItem(A1, Map.of("productId", DevFixtures.BURGER, "quantity", 2,
                "removedIngredientIds", List.of(DevFixtures.OPT_ONION),
                "extras", List.of(extra(DevFixtures.OPT_CHEESE, 2)), "note", "well done"))
                .andExpect(status().isOk());
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        var response = mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + A1)
                        .header("Idempotency-Key", newKey()).header("X-Correlation-Id", correlationId)
                        .contentType("application/json")
                        .content(toJson(Map.of("expectedCartVersion", body.get("expectedCartVersion"),
                                "expectedTotalMinor", body.get("expectedTotalMinor"), "note", "table by the window"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    @Test
    void eventDescribesTheOrderForItsConsumers() throws Exception {
        JsonNode order = placeCustomizedOrder("trace-event-1");

        var meta = jdbc.queryForMap("SELECT * FROM outbox_events WHERE event_type = 'OrderPlaced'");
        JsonNode event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE event_type = 'OrderPlaced'", String.class));

        assertEquals("OrderPlaced", meta.get("event_type"));
        assertEquals(1, meta.get("schema_version"));
        assertEquals("ORDER", meta.get("aggregate_type"));
        assertEquals(UUID.fromString(order.path("orderId").asString()), meta.get("aggregate_id"));
        assertEquals(0L, meta.get("aggregate_version"));
        assertEquals("trace-event-1", meta.get("correlation_id"));
        assertEquals(DevFixtures.RESTAURANT_A, meta.get("restaurant_id"));

        // envelope
        assertEquals(meta.get("id").toString(), event.path("eventId").asString());
        assertEquals("OrderPlaced", event.path("eventType").asString());
        assertEquals(1, event.path("schemaVersion").asInt());
        assertEquals("trace-event-1", event.path("correlationId").asString());
        assertTrue(event.path("occurredAt").asString().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"),
                "timestamps are ISO-8601 strings: " + event.path("occurredAt"));
        // identity and binding
        assertEquals(DevFixtures.RESTAURANT_A.toString(), event.path("restaurantId").asString());
        assertEquals(DevFixtures.TABLE_A1.toString(), event.path("tableId").asString());
        assertEquals(DevFixtures.TABLE_SESSION_A1.toString(), event.path("tableSessionId").asString());
        assertEquals(DevFixtures.GUEST_A1.toString(), event.path("guestSessionId").asString());
        assertEquals(order.path("orderId").asString(), event.path("orderId").asString());
        assertEquals(order.path("orderNo").asString(), event.path("orderNo").asString());
        assertEquals("RECEIVED", event.path("fulfillmentStatus").asString());
        assertEquals("table by the window", event.path("note").asString());
        assertEquals(order.path("amounts").path("grossMinor").asLong(), event.path("amounts").path("grossMinor").asLong());
        // what Kitchen needs: products, quantities, notes and ingredient changes
        JsonNode burger = event.path("items").get(0);
        assertEquals("Dev Burger", burger.path("name").asString());
        assertEquals(2, burger.path("quantity").asInt());
        assertEquals("well done", burger.path("note").asString());
        assertEquals("Onion", burger.path("removedIngredients").get(0).path("name").asString());
        assertEquals("Extra cheese", burger.path("extras").get(0).path("name").asString());
        assertEquals(2, burger.path("extras").get(0).path("quantity").asInt());
        assertEquals(2, event.path("items").size());
    }

    @Test
    void oneDeliveryPerDestinationIsRecordedAndNothingIsClaimedDelivered() throws Exception {
        placeCustomizedOrder("trace-event-2");
        String placed = " FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id"
                + " WHERE e.event_type = 'OrderPlaced'";
        Set<String> destinations = jdbc.queryForList("SELECT d.destination" + placed, String.class)
                .stream().collect(Collectors.toSet());
        assertEquals(Set.of("KITCHEN", "QR", "NOTIFICATION", "WAITER"), destinations);
        assertEquals(4, jdbc.queryForObject("SELECT count(*)" + placed + " AND d.status = 'PENDING'"
                + " AND d.attempt_count = 0 AND d.delivered_at IS NULL AND d.next_attempt_at IS NOT NULL",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE status = 'DELIVERED'",
                Integer.class), "no dispatcher exists, so nothing may be marked delivered");
    }

    @Test
    void eventNeverContainsCredentialsOrClientSuppliedSecrets() throws Exception {
        placeCustomizedOrder("trace-event-3");
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE event_type = 'OrderPlaced'", String.class).toLowerCase();
        for (String forbidden : new String[]{"token", "bearer", "authorization", "password", "secret",
                A1.toLowerCase(), "idempotency"}) {
            assertFalse(payload.contains(forbidden), "event payload must not contain '" + forbidden + "'");
        }
    }

    @Test
    void replayingARequestDoesNotEmitASecondEvent() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        Map<String, Object> body = confirmedCheckout(A1);
        String key = newKey();
        placeOrder(A1, key, body).andExpect(status().isCreated());
        placeOrder(A1, key, body).andExpect(status().isCreated());
        assertEquals(1, count("outbox_events"));
        assertEquals(4, count("outbox_deliveries"));
        assertNotNull(jdbc.queryForObject("SELECT id FROM outbox_events WHERE event_type = 'OrderPlaced'", UUID.class));
    }

    @Test
    void failedCheckoutsEmitNoEvent() throws Exception {
        addItem(A1, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(A1, newKey(), Map.of("expectedCartVersion", 99, "expectedTotalMinor", 1))
                .andExpect(status().isConflict());
        assertEquals(0, count("outbox_events"));
        assertEquals(0, count("outbox_deliveries"));
    }
}
