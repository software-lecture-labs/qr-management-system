package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract of the five cancellation events (K-13) as they are really written to the outbox, and that the records
 * behind the report cannot be rewritten or lost.
 */
class OrderCancellationEventContentApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    @Autowired
    private DataRetentionService retention;

    private JsonNode event(UUID orderId, String type) throws Exception {
        return json.readTree(payloadOf(orderId, type));
    }

    private void assertEnvelope(JsonNode event, UUID orderId, String type) {
        assertEquals(type, event.path("eventType").asString());
        assertEquals(1, event.path("schemaVersion").asInt());
        assertEquals(orderId.toString(), event.path("orderId").asString());
        assertEquals(DevFixtures.RESTAURANT_A.toString(), event.path("restaurantId").asString());
        assertFalse(event.path("eventId").asString().isBlank());
        assertFalse(event.path("occurredAt").asString().isBlank());
        assertFalse(event.path("orderNo").asString().isBlank());
        assertFalse(event.path("cancellationId").asString().isBlank());
        assertFalse(event.path("fulfillmentStatus").asString().isBlank());
        assertFalse(event.path("paymentStatus").asString().isBlank());
    }

    private void assertNoIdentityOrSecret(UUID orderId) {
        for (String payload : jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type LIKE 'OrderCancel%'", String.class, orderId)) {
            for (String secret : List.of(DevFixtures.GUEST_A1.toString(), DevFixtures.USER_WAITER_A.toString(),
                    DevFixtures.USER_ADMIN_A.toString(), A1, DevFixtures.STAFF_WAITER_A, KITCHEN_CREDENTIAL,
                    PAYMENT_CREDENTIAL)) {
                assertFalse(payload.contains(secret), "no identifier or credential in an event: " + secret);
            }
        }
    }

    @Test
    void theCancelledEventCarriesTheFullCancellationAndNoIdentity() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelAllWith(List.of("CHANGED_MIND"), "changed my mind"))
                .andExpect(status().isCreated());

        JsonNode cancelled = event(orderId, "OrderCancelled");
        assertEnvelope(cancelled, orderId, "OrderCancelled");
        assertEquals("CANCELLED", cancelled.path("fulfillmentStatus").asString());
        assertEquals("CANCELLED", cancelled.path("paymentStatus").asString());
        assertEquals("RECEIVED", cancelled.path("previousFulfillmentStatus").asString());
        assertEquals(SALAD_DUE, cancelled.path("previousDueMinor").asLong());
        assertEquals(0L, cancelled.path("newDueMinor").asLong());
        assertEquals(SALAD_DUE, cancelled.path("cancelledAmountMinor").asLong());
        assertEquals("TRY", cancelled.path("currency").asString());
        assertEquals(0L, cancelled.path("refundRequiredMinor").asLong());
        assertFalse(cancelled.path("refundReviewRequired").asBoolean());
        assertEquals("GUEST", cancelled.path("requestedBy").path("type").asString());
        assertEquals("SYSTEM", cancelled.path("decidedBy").path("type").asString());
        assertFalse(cancelled.path("decidedAt").asString().isBlank());
        JsonNode line = cancelled.path("items").get(0);
        assertEquals("Dev Salad", line.path("name").asString());
        assertEquals(1, line.path("quantity").asInt());
        assertEquals(0, line.path("remainingQuantity").asInt());
        assertEquals(SALAD_DUE, line.path("amountMinor").asLong());
        assertEquals(2, cancelled.path("reasons").size());
        assertEquals("GUEST", cancelled.path("reasons").get(0).path("source").asString());
        assertEquals("CHANGED_MIND", cancelled.path("reasons").get(0).path("code").asString());
        assertEquals("changed my mind", cancelled.path("reasons").get(1).path("text").asString());
        assertNoIdentityOrSecret(orderId);
    }

    @Test
    void thePartialAndAdjustedEventsDescribeWhatChangedAndWhatIsOwed() throws Exception {
        UUID orderId = placeAndPayMixedOrder();
        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated());

        JsonNode items = event(orderId, "OrderItemsCancelled");
        assertEnvelope(items, orderId, "OrderItemsCancelled");
        assertEquals("RECEIVED", items.path("fulfillmentStatus").asString());
        assertEquals("PAID", items.path("paymentStatus").asString());
        assertEquals(1, items.path("items").size());
        assertEquals("Dev Burger", items.path("items").get(0).path("name").asString());
        assertEquals(0, items.path("items").get(0).path("remainingQuantity").asInt());
        assertEquals(25_000L, items.path("cancelledAmountMinor").asLong());
        assertEquals(25_000L, items.path("refundRequiredMinor").asLong());
        assertEquals("STAFF", items.path("requestedBy").path("type").asString());
        assertEquals("WAITER", items.path("requestedBy").path("role").asString());

        JsonNode adjusted = event(orderId, "OrderAmountAdjusted");
        assertEnvelope(adjusted, orderId, "OrderAmountAdjusted");
        assertEquals("PARTIAL_CANCELLATION", adjusted.path("adjustmentReason").asString());
        assertEquals(MIXED_TOTAL, adjusted.path("originalGrossMinor").asLong(), "the original total never changes");
        assertEquals(25_000L, adjusted.path("cancelledTotalMinor").asLong());
        assertEquals("PAID", adjusted.path("paymentStatusAtAdjustment").asString());
        assertEquals(25_000L, adjusted.path("refundRequiredMinor").asLong());
        assertNoIdentityOrSecret(orderId);
    }

    @Test
    void theRequestedAndRejectedEventsTellTheKitchenWhatToDecide() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID cancellation = requestIdOf(cancel(guest(A1), orderId, newKey(),
                cancelItems(lineOf(orderId, DevFixtures.SALAD), 2)).andExpect(status().isCreated()));

        JsonNode requested = event(orderId, "OrderCancellationRequested");
        assertEnvelope(requested, orderId, "OrderCancellationRequested");
        assertEquals(cancellation.toString(), requested.path("cancellationId").asString());
        assertEquals("PARTIAL", requested.path("scope").asString());
        assertTrue(requested.path("kitchenConfirmationRequired").asBoolean());
        assertEquals("PREPARING", requested.path("fulfillmentStatus").asString(), "nothing was cancelled yet");
        assertEquals(MIXED_TOTAL, requested.path("previousDueMinor").asLong());
        assertEquals(34_000L, requested.path("projectedDueMinor").asLong());
        assertEquals(1, requested.path("items").get(0).path("remainingQuantity").asInt(),
                "what would remain if it is approved");

        kitchenDecides(orderId, cancellation, "REJECTED", "ALREADY_COOKED").andExpect(status().isOk());
        JsonNode rejected = event(orderId, "OrderCancellationRejected");
        assertEnvelope(rejected, orderId, "OrderCancellationRejected");
        assertEquals("ALREADY_COOKED", rejected.path("rejectionCode").asString());
        assertEquals("SERVICE", rejected.path("rejectedBy").path("type").asString());
        assertEquals("KITCHEN", rejected.path("rejectedBy").path("role").asString());
        assertEquals("GUEST", rejected.path("requestedBy").path("type").asString());
        assertEquals(3, rejected.path("items").get(0).path("remainingQuantity").asInt(), "nothing was cancelled");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type IN ('OrderCancelled', 'OrderItemsCancelled', 'OrderAmountAdjusted')", Integer.class,
                orderId), "a rejection announces no cancellation and no amount change");
        assertNoIdentityOrSecret(orderId);
    }

    @Test
    void everyEventHasItsOwnIdItsOrdersVersionAndPendingDeliveriesOnly() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        List<Map<String, Object>> events = jdbc.queryForList("SELECT id, aggregate_type, aggregate_version, schema_version,"
                + " correlation_id FROM outbox_events WHERE aggregate_id = ? AND event_type LIKE 'OrderCancel%'"
                + " OR (aggregate_id = ? AND event_type = 'OrderAmountAdjusted')", orderId, orderId);
        assertEquals(2, events.size());
        assertEquals(2, events.stream().map(e -> e.get("id")).distinct().count());
        long version = versionOf(orderId);
        for (Map<String, Object> e : events) {
            assertEquals("ORDER", e.get("aggregate_type"));
            assertEquals(version, ((Number) e.get("aggregate_version")).longValue(),
                    "consumers order events of an order by this version");
            assertEquals(1, e.get("schema_version"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e"
                + " ON e.id = d.event_id WHERE e.aggregate_id = ? AND e.event_type LIKE 'OrderCancel%'"
                + " AND (d.status <> 'PENDING' OR d.attempt_count <> 0)", Integer.class, orderId),
                "nothing was delivered, so nothing is claimed delivered");
    }

    // ---- the records cannot be rewritten or lost ---------------------------------------------------------------------

    @Test
    void theCancellationRecordsStayWhatTheyWereWhateverHappensToTheOrderAfterwards() throws Exception {
        UUID orderId = placeAndPayMixedOrder();
        UUID cancellation = requestIdOf(cancelAll(WAITER, orderId).andExpect(status().isCreated()));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM order_cancellations WHERE id = ?", cancellation);
        int items = count("order_cancellation_items");

        // late events for the cancelled order change nothing of the record
        pay(orderId, "CANCELLED", 1).andExpect(result -> assertTrue(result.getResponse().getStatus() < 500));
        kitchenEventFor(orderId, "READY").andExpect(status().isOk());
        kitchenDecides(orderId, cancellation, "APPROVED", null).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        cancelAll(WAITER, orderId).andExpect(status().isConflict());

        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM order_cancellations WHERE id = ?", cancellation);
        assertEquals(before, after);
        assertEquals(items, count("order_cancellation_items"));
        assertEquals(MIXED_TOTAL, ((Number) after.get("refund_required_minor")).longValue());
    }

    @Test
    void aKitchenDecisionRedeliveredAfterTheInboxWindowIsHarmless() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID eventId = UUID.randomUUID();
        var body = decisionBody(eventId, waiting.orderId(), waiting.cancellationId(), "APPROVED", null);
        kitchenDecision(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk());
        int events = count("outbox_events");

        // the message memory (90 days) forgets the event; the decision itself is final and is never applied twice
        jdbc.update("UPDATE inbox_events SET received_at = ?", Timestamp.from(Instant.now().minus(120, ChronoUnit.DAYS)));
        assertEquals(1, retention.purgeProcessedInboxEvents());
        kitchenDecision(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"))
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));

        assertEquals(events, count("outbox_events"));
        assertEquals("CANCELLED", fulfillmentOf(waiting.orderId()));
        assertEquals(4, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, waiting.orderId()), "units are not cancelled a second time");
    }
}
