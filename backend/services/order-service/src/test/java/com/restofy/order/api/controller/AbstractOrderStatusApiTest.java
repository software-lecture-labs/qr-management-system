package com.restofy.order.api.controller;

import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Helpers for the fulfillment status tests: staff PATCH, Kitchen events, and reading back what was written. */
abstract class AbstractOrderStatusApiTest extends AbstractCartApiTest {

    protected static final String KITCHEN_CREDENTIAL = InMemoryServiceIdentityAdapter.KITCHEN_CREDENTIAL;
    protected static final String KITCHEN_EVENTS = "/api/v1/internal/order-events/kitchen";
    protected static final String PAYMENT_CREDENTIAL = InMemoryServiceIdentityAdapter.PAYMENT_CREDENTIAL;
    protected static final String PAYMENT_EVENTS = "/api/v1/internal/order-events/payment";
    /** What a salad order costs (the mock catalog): 90.00 TRY, KDV included. */
    protected static final long SALAD_DUE = 9_000L;

    protected ResultActions patchStatus(String authorization, UUID orderId, String status) throws Exception {
        return patchStatus(authorization, orderId, Map.of("status", status));
    }

    protected ResultActions patchStatus(String authorization, UUID orderId, Object body) throws Exception {
        var request = patch("/api/v1/orders/" + orderId + "/fulfillment-status")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected ResultActions kitchenEvent(String authorization, Object body) throws Exception {
        var request = post(KITCHEN_EVENTS).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    protected ResultActions paymentEvent(String authorization, Object body) throws Exception {
        var request = post(PAYMENT_EVENTS).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    // The test acts as the Payment Service: one current attempt per order (a PENDING after a FAILED/CANCELLED starts a new
    // one) and one capture per order (re-sending PAID is the same money). Tests that need other references use
    // paymentBodyWith.
    private final Map<UUID, UUID> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> captures = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastPaymentStatus = new ConcurrentHashMap<>();

    protected UUID attemptOf(UUID orderId) {
        return attempts.computeIfAbsent(orderId, id -> UUID.randomUUID());
    }

    protected UUID captureOf(UUID orderId) {
        return captures.computeIfAbsent(orderId, id -> UUID.randomUUID());
    }

    protected Map<String, Object> paymentBody(UUID eventId, UUID orderId, String status, Long amount, String currency,
                                              Instant occurredAt) {
        String previous = lastPaymentStatus.put(orderId, status);
        if ("PENDING".equals(status) && ("FAILED".equals(previous) || "CANCELLED".equals(previous))) {
            attempts.remove(orderId);
        }
        return paymentBodyWith(eventId, orderId, status, amount, currency, occurredAt, attemptOf(orderId),
                "PAID".equals(status) ? captureOf(orderId) : null);
    }

    protected static Map<String, Object> paymentBodyWith(UUID eventId, UUID orderId, String status, Long amount,
                                                         String currency, Instant occurredAt, UUID attemptId,
                                                         UUID captureId) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("restaurantId", DevFixtures.RESTAURANT_A);
        body.put("orderId", orderId);
        body.put("paymentStatus", status);
        body.put("paymentAttemptId", attemptId);
        body.put("occurredAt", occurredAt);
        if (captureId != null) {
            body.put("captureId", captureId);
        }
        if (amount != null) {
            body.put("amountMinor", amount);
        }
        if (currency != null) {
            body.put("currency", currency);
        }
        return body;
    }

    protected ResultActions payWith(UUID orderId, String status, long secondsAgo, UUID attemptId, UUID captureId)
            throws Exception {
        boolean amount = status.equals("PENDING") || status.equals("PAID");
        return paymentEvent(service(PAYMENT_CREDENTIAL), paymentBodyWith(UUID.randomUUID(), orderId, status,
                amount ? SALAD_DUE : null, amount ? "TRY" : null, Instant.now().minusSeconds(secondsAgo), attemptId,
                captureId));
    }

    /** A payment event for a salad order with the right amount, a fresh event id and the given offset from now. */
    protected ResultActions pay(UUID orderId, String status, long secondsAgo) throws Exception {
        boolean amount = status.equals("PENDING") || status.equals("PAID");
        return paymentEvent(service(PAYMENT_CREDENTIAL), paymentBody(UUID.randomUUID(), orderId, status,
                amount ? SALAD_DUE : null, amount ? "TRY" : null, Instant.now().minusSeconds(secondsAgo)));
    }

    protected static Map<String, Object> kitchenBody(UUID eventId, UUID restaurantId, UUID orderId, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("restaurantId", restaurantId);
        body.put("orderId", orderId);
        body.put("kitchenStatus", status);
        return body;
    }

    protected ResultActions kitchenEventFor(UUID orderId, String status) throws Exception {
        return kitchenEvent(service(KITCHEN_CREDENTIAL),
                kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, status));
    }

    /** Moves an order along the line with the roles that are allowed to, so a test can start from any status. */
    protected void advanceTo(UUID orderId, String target) throws Exception {
        List<String> line = List.of("PREPARING", "READY", "SERVED", "COMPLETED");
        for (String step : line) {
            String credential = switch (step) {
                case "PREPARING", "READY" -> DevFixtures.STAFF_KITCHEN_A;
                default -> DevFixtures.STAFF_WAITER_A;
            };
            patchStatus(staff(credential), orderId, step)
                    .andExpect(MockMvcResultMatchers.status().isOk());
            if (step.equals(target)) {
                return;
            }
        }
    }

    protected String fulfillmentOf(UUID orderId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM orders WHERE id = ?", String.class, orderId);
    }

    /** The payment history rows of an order, oldest first, as "FROM>TO|ACTOR_TYPE|SOURCE". */
    protected List<String> paymentHistory(UUID orderId) {
        return jdbc.queryForList("""
                SELECT coalesce(from_status, '-') || '>' || to_status || '|' || actor_type || '|' || source
                FROM order_status_history WHERE order_id = ? AND state_axis = 'PAYMENT' ORDER BY seq
                """, String.class, orderId);
    }

    /** Destinations of the OrderPaymentStatusChanged events of an order that moved to the given payment status. */
    protected List<String> destinationsOfPaymentChangeTo(UUID orderId, String toStatus) {
        return jdbc.queryForList("""
                SELECT d.destination FROM outbox_events e JOIN outbox_deliveries d ON d.event_id = e.id
                WHERE e.aggregate_id = ? AND e.event_type = 'OrderPaymentStatusChanged'
                  AND e.payload->>'toStatus' = ? ORDER BY d.destination
                """, String.class, orderId, toStatus);
    }

    protected int paymentChangeEvents(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderPaymentStatusChanged'", Integer.class, orderId);
    }

    protected String paymentOf(UUID orderId) {
        return jdbc.queryForObject("SELECT payment_status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected long versionOf(UUID orderId) {
        return jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId);
    }

    /** The fulfillment history rows of an order, oldest first, as "FROM>TO|ACTOR_TYPE|SOURCE". */
    protected List<String> fulfillmentHistory(UUID orderId) {
        return jdbc.queryForList("""
                SELECT coalesce(from_status, '-') || '>' || to_status || '|' || actor_type || '|' || source
                FROM order_status_history WHERE order_id = ? AND state_axis = 'FULFILLMENT' ORDER BY seq
                """, String.class, orderId);
    }

    /** Destinations of the OrderFulfillmentStatusChanged events of an order that moved to the given status. */
    protected List<String> destinationsOfChangeTo(UUID orderId, String toStatus) {
        return jdbc.queryForList("""
                SELECT d.destination FROM outbox_events e JOIN outbox_deliveries d ON d.event_id = e.id
                WHERE e.aggregate_id = ? AND e.event_type = 'OrderFulfillmentStatusChanged'
                  AND e.payload->>'toStatus' = ? ORDER BY d.destination
                """, String.class, orderId, toStatus);
    }

    protected int changeEvents(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderFulfillmentStatusChanged'", Integer.class, orderId);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String registerSecondWaiter() {
        auth.register("dev-mock-staff-waiter-a2",
                new StaffActor(UUID.randomUUID(), DevFixtures.RESTAURANT_A, StaffRole.WAITER));
        return "dev-mock-staff-waiter-a2";
    }
}
