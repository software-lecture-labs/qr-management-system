package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for the cancellation tests. The mixed order is 3 salads (9,000 each, VAT 20%) and 1 burger (25,000, VAT 10%):
 * 52,000 in total, so a partial cancellation has lines, quantities and VAT rates to work with.
 */
abstract class AbstractCancellationApiTest extends AbstractOrderStatusApiTest {

    protected static final String A1 = DevFixtures.TOKEN_A1;
    protected static final long MIXED_TOTAL = 52_000L;

    protected UUID placeMixedOrder(String guestToken) throws Exception {
        addItem(guestToken, product(DevFixtures.SALAD, 3)).andExpect(status().isOk());
        addItem(guestToken, product(DevFixtures.BURGER, 1)).andExpect(status().isOk());
        String body = placeOrder(guestToken, newKey(), confirmedCheckout(guestToken))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("orderId").asString());
    }

    protected UUID lineOf(UUID orderId, UUID productId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND product_id = ?", UUID.class,
                orderId, productId);
    }

    protected ResultActions cancel(String authorization, UUID orderId, String key, Object body) throws Exception {
        var request = post("/api/v1/orders/" + orderId + "/cancellations").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    protected ResultActions cancelAll(String authorization, UUID orderId) throws Exception {
        return cancel(authorization, orderId, newKey(), Map.of("cancelAll", true));
    }

    protected static Map<String, Object> cancelAllWith(List<String> codes, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("cancelAll", true);
        if (codes != null) {
            body.put("reasonCodes", codes);
        }
        if (text != null) {
            body.put("reasonText", text);
        }
        return body;
    }

    protected static Map<String, Object> cancelItems(Object... lineAndQuantity) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < lineAndQuantity.length; i += 2) {
            items.add(Map.of("orderItemId", lineAndQuantity[i], "quantity", lineAndQuantity[i + 1]));
        }
        return Map.of("items", items);
    }

    /** A guest orders and pays the whole mixed order (the test acts as the Payment Service). */
    protected UUID placeAndPayMixedOrder() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        payAmount(orderId, "PENDING", MIXED_TOTAL).andExpect(status().isOk());
        payAmount(orderId, "PAID", MIXED_TOTAL).andExpect(status().isOk());
        return orderId;
    }

    protected ResultActions payAmount(UUID orderId, String status, long amount) throws Exception {
        return paymentEvent(service(PAYMENT_CREDENTIAL), paymentBody(UUID.randomUUID(), orderId, status, amount, "TRY",
                Instant.now().minusSeconds(5)));
    }

    protected JsonNode bodyOf(ResultActions result) throws Exception {
        return body(result);
    }

    protected List<String> eventTypes(UUID orderId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY event_type",
                String.class, orderId).stream().filter(type -> type.contains("Cancel") || type.contains("Amount"))
                .toList();
    }

    protected List<String> destinationsOf(UUID orderId, String eventType) {
        return jdbc.queryForList("""
                SELECT d.destination FROM outbox_events e JOIN outbox_deliveries d ON d.event_id = e.id
                WHERE e.aggregate_id = ? AND e.event_type = ? ORDER BY d.destination
                """, String.class, orderId, eventType);
    }

    protected String payloadOf(UUID orderId, String eventType) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                String.class, orderId, eventType);
    }

    protected static final String KITCHEN_CANCELLATION_EVENTS = "/api/v1/internal/order-events/kitchen-cancellation";

    protected ResultActions kitchenDecision(String authorization, Object body) throws Exception {
        var request = post(KITCHEN_CANCELLATION_EVENTS).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected static Map<String, Object> decisionBody(UUID eventId, UUID orderId, UUID cancellationId, String decision,
                                                      String rejectionCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("restaurantId", DevFixtures.RESTAURANT_A);
        body.put("orderId", orderId);
        body.put("cancellationId", cancellationId);
        body.put("decision", decision);
        if (rejectionCode != null) {
            body.put("rejectionCode", rejectionCode);
        }
        return body;
    }

    /** The Kitchen service answers a waiting request. */
    protected ResultActions kitchenDecides(UUID orderId, UUID cancellationId, String decision, String rejectionCode)
            throws Exception {
        return kitchenDecision(service(KITCHEN_CREDENTIAL),
                decisionBody(UUID.randomUUID(), orderId, cancellationId, decision, rejectionCode));
    }

    /** Orders a salad, moves it into preparation and has the guest ask to cancel it; returns the waiting request. */
    protected record Waiting(UUID orderId, UUID cancellationId) {
    }

    protected Waiting waitingRequest(Object body) throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID cancellationId = requestIdOf(cancel(guest(A1), orderId, newKey(), body).andExpect(status().isCreated()));
        return new Waiting(orderId, cancellationId);
    }

    protected UUID requestIdOf(ResultActions created) throws Exception {
        return UUID.fromString(body(created).path("cancellationId").asString());
    }

    protected String cancellationStatus(UUID cancellationId) {
        return jdbc.queryForObject("SELECT status FROM order_cancellations WHERE id = ?", String.class, cancellationId);
    }

    protected int cancellationCount(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_cancellations WHERE order_id = ?", Integer.class,
                orderId);
    }
}
