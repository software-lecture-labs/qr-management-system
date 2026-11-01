package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KARAR 02: the Kitchen service's OWN cancellation request ({@code /kitchen-cancellation-request}). Receiving it never
 * cancels by itself; the Order Service applies its rules and is the only authority, either cancelling or refusing and
 * recording the refusal.
 */
class KitchenCancellationRequestApiTests extends AbstractCancellationApiTest {

    private static final String PATH = "/api/v1/internal/order-events/kitchen-cancellation-request";
    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);

    private ResultActions kitchenAsks(String authorization, Object body)
            throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private ResultActions kitchenAsks(Object body) throws Exception {
        return kitchenAsks(service(KITCHEN_CREDENTIAL), body);
    }

    private static Map<String, Object> askAll(UUID eventId, UUID orderId, List<String> codes, String text) {
        Map<String, Object> body = base(eventId, orderId, codes, text);
        body.put("cancelAll", true);
        return body;
    }

    private static Map<String, Object> askItems(UUID eventId, UUID orderId, UUID line, int quantity, List<String> codes,
                                                String text) {
        Map<String, Object> body = base(eventId, orderId, codes, text);
        body.put("items", List.of(Map.of("orderItemId", line, "quantity", quantity)));
        return body;
    }

    private static Map<String, Object> base(UUID eventId, UUID orderId, List<String> codes, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("restaurantId", DevFixtures.RESTAURANT_A);
        body.put("orderId", orderId);
        if (codes != null) {
            body.put("reasonCodes", codes);
        }
        if (text != null) {
            body.put("reasonText", text);
        }
        return body;
    }

    private static List<String> outOfStock() {
        return List.of("ITEM_UNAVAILABLE");
    }

    // ---- the Order Service decides, and a valid request is applied -------------------------------------------------

    @Test
    void aValidRequestForAReceivedOrderIsCancelledByTheOrderServiceAndFullyRecorded() throws Exception {
        UUID orderId = placeMixedOrder(A1);

        kitchenAsks(askAll(UUID.randomUUID(), orderId, List.of("ITEM_UNAVAILABLE", "OTHER"), "no more bread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));

        assertEquals("CANCELLED", fulfillmentOf(orderId));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, requested_by_type, requested_by_role, requested_by_id,"
                + " decision_source, decided_by_type, kitchen_confirmation_required, cancelled_gross_minor,"
                + " refund_required_minor FROM order_cancellations WHERE order_id = ?", orderId);
        assertEquals("APPLIED", row.get("status"));
        // the requester is stored as what it is: the verified Kitchen SERVICE, not a staff member
        assertEquals("SERVICE", row.get("requested_by_type"));
        assertEquals("KITCHEN", row.get("requested_by_role"));
        assertEquals("KITCHEN", row.get("requested_by_id"));
        assertEquals(null, jdbc.queryForObject("SELECT reported_staff_id FROM order_cancellations WHERE order_id = ?",
                UUID.class, orderId), "the Kitchen named nobody");
        assertEquals("KITCHEN", row.get("decision_source"));
        assertEquals("SYSTEM", row.get("decided_by_type"), "the Order Service decided");
        assertEquals(false, row.get("kitchen_confirmation_required"));
        assertEquals(MIXED_TOTAL, row.get("cancelled_gross_minor"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE source = 'SERVICE'"
                + " AND added_by_type = 'SERVICE' AND added_by_role = 'KITCHEN' AND added_by_id = 'KITCHEN'",
                Integer.class));
        assertTrue(fulfillmentHistory(orderId).contains("RECEIVED>CANCELLED|SERVICE|CANCELLATION"),
                fulfillmentHistory(orderId).toString());
        assertEquals(List.of("OrderAmountAdjusted", "OrderCancelled"), eventTypes(orderId));
        // the Kitchen asked for it, so it is not sent its own request back
        assertEquals(List.of("ADMIN", "NOTIFICATION", "PAYMENT", "QR", "WAITER"), destinationsOf(orderId, "OrderCancelled"));
        JsonNode event = json.readTree(payloadOf(orderId, "OrderCancelled"));
        assertEquals("SERVICE", event.path("requestedBy").path("type").asString());
        assertEquals("KITCHEN", event.path("requestedBy").path("role").asString());
        assertEquals("SYSTEM", event.path("decidedBy").path("type").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE source = 'KITCHEN'"
                + " AND event_type = 'KitchenCancellationRequest'", Integer.class));

        getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("SERVICE"))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.role").value("KITCHEN"))
                .andExpect(jsonPath("$.cancellations[0].reasonPending").value(false))
                .andExpect(jsonPath("$.cancellations[0].reasons[*].source", contains("SERVICE", "SERVICE", "SERVICE")))
                .andExpect(jsonPath("$.cancellations[0].reasons[0].addedBy.type").value("SERVICE"))
                .andExpect(jsonPath("$.cancellations[0].decidedBy.type").value("SYSTEM"))
                .andExpect(jsonPath("$.cancellations[0].decisionSource").value("KITCHEN"));
    }

    @Test
    void anOrderInPreparationIsCancelledOnTheKitchensOwnRequestWithoutWaiting() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "PREPARING");

        kitchenAsks(askAll(UUID.randomUUID(), orderId, List.of("KITCHEN_ERROR"), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertTrue(fulfillmentHistory(orderId).contains("PREPARING>CANCELLED|SERVICE|CANCELLATION"));
        assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM order_cancellations WHERE order_id = ?",
                String.class, orderId));
    }

    @Test
    void aPartialRequestCancelsOnlyTheNamedUnitsAndNamesTheKitchenStaffMember() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID cook = UUID.randomUUID();
        var body = askItems(UUID.randomUUID(), orderId, lineOf(orderId, DevFixtures.SALAD), 2, List.of("KITCHEN_ERROR"),
                "dropped on the floor");
        body.put("requestedByStaffId", cook);

        kitchenAsks(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.scope").value("PARTIAL")).andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"));

        assertEquals(34_000L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(2, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
        assertEquals(List.of("OrderAmountAdjusted", "OrderItemsCancelled"), eventTypes(orderId));
        // the verified service identity is the author; the staff member is a claim of the service, kept apart
        assertEquals("KITCHEN", jdbc.queryForObject("SELECT requested_by_id FROM order_cancellations WHERE order_id = ?",
                String.class, orderId));
        assertEquals(cook, jdbc.queryForObject("SELECT reported_staff_id FROM order_cancellations WHERE order_id = ?",
                UUID.class, orderId));
        getWith(ADMIN, "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("SERVICE"))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.role").value("KITCHEN"))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.id").value("KITCHEN"))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.reportedStaffId").value(cook.toString()));
        getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].requestedBy.id").doesNotExist())
                .andExpect(jsonPath("$.cancellations[0].requestedBy.reportedStaffId").doesNotExist());
        // the claimed staff member appears in no event
        assertFalse(payloadOf(orderId, "OrderItemsCancelled").contains(cook.toString()));
    }

    @Test
    void theRequestAndTheDecisionAreSeparateFactsOfTheSameRecord() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();

        kitchenAsks(askAll(eventId, orderId, outOfStock(), null)).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("SELECT requested_by_type, requested_by_role, requested_by_id,"
                + " requested_at, decision_source, decided_by_type, decided_by_id, decided_at FROM order_cancellations"
                + " WHERE order_id = ?", orderId);
        // who asked: the verified Kitchen SERVICE; who decided: the Order Service itself, on its own rules
        assertEquals("SERVICE", row.get("requested_by_type"));
        assertEquals("KITCHEN", row.get("requested_by_role"));
        assertEquals("KITCHEN", row.get("requested_by_id"));
        assertEquals("SYSTEM", row.get("decided_by_type"));
        assertEquals(null, row.get("decided_by_id"));
        assertEquals("KITCHEN", row.get("decision_source"), "decided on the basis of the Kitchen's own request");
        assertFalse(((Timestamp) row.get("decided_at")).before((Timestamp) row.get("requested_at")),
                "the decision is never earlier than the request");
        // the receipt of the request is remembered apart from the decision
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE source = 'KITCHEN' AND event_id = ?"
                + " AND event_type = 'KitchenCancellationRequest'", Integer.class, eventId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation ="
                + " 'KITCHEN_CANCEL_REQUEST' AND actor_scope = 'service:KITCHEN' AND idempotency_key = ?", Integer.class,
                eventId.toString()));
        assertTrue(fulfillmentHistory(orderId).contains("RECEIVED>CANCELLED|SERVICE|CANCELLATION"));
        getWith(ADMIN, "/api/v1/admin/order-cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestedBy.type").value("SERVICE"))
                .andExpect(jsonPath("$.items[0].requestedBy.role").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].requestedBy.id").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].decisionSource").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].reasons[0].source").value("SERVICE"))
                .andExpect(jsonPath("$.items[0].decidedAt").exists());
    }

    @Test
    void theKitchensPolicyAcrossEveryFulfillmentAndPaymentState() throws Exception {
        // RECEIVED and PREPARING, not paid: the Order Service cancels on the Kitchen's request
        for (String state : List.of("RECEIVED", "PREPARING")) {
            UUID orderId = placeSaladOrder(A1);
            if (state.equals("PREPARING")) {
                advanceTo(orderId, "PREPARING");
            }
            kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("APPLIED"));
            assertEquals("CANCELLED", fulfillmentOf(orderId), state);
        }
        // READY, SERVED, COMPLETED (D-02): refused and the refusal recorded
        for (String state : List.of("READY", "SERVED", "COMPLETED")) {
            UUID orderId = placeSaladOrder(A1);
            advanceTo(orderId, state);
            kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("REJECTED"))
                    .andExpect(jsonPath("$.rejectionCode").value("ORDER_ALREADY_PREPARED"));
            assertEquals(state, fulfillmentOf(orderId));
        }
        // paid, in either state that could be cancelled: never on the Kitchen's word
        for (String state : List.of("RECEIVED", "PREPARING")) {
            UUID orderId = placeSaladOrder(A1);
            pay(orderId, "PENDING", 10).andExpect(status().isOk());
            pay(orderId, "PAID", 5).andExpect(status().isOk());
            if (state.equals("PREPARING")) {
                advanceTo(orderId, "PREPARING");
            }
            kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("REJECTED"))
                    .andExpect(jsonPath("$.rejectionCode").value("ORDER_ALREADY_PAID"));
            assertEquals(state, fulfillmentOf(orderId));
            assertEquals("PAID", paymentOf(orderId));
        }
    }

    @Test
    void anUnpaidOrderWithAPendingPaymentIsCancelledAndALateCollectionBecomesAReconciliationCase() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());

        kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED")).andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
        pay(orderId, "PAID", 1).andExpect(status().isOk());

        assertEquals("CANCELLED", paymentOf(orderId));
        assertEquals(1, count("payment_reconciliation_cases"));
        assertEquals(0L, jdbc.queryForObject("SELECT sum(refunded_minor) FROM order_cancellations", Long.class),
                "no refund is claimed to have happened");
    }

    @Test
    void theKitchensRequestAndAPaymentRacingLeaveAConsistentOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var kitchen = pool.submit(() -> {
                start.await();
                return kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andReturn().getResponse()
                        .getStatus();
            });
            var payment = pool.submit(() -> {
                start.await();
                return pay(orderId, "PAID", 1).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(200, kitchen.get(30, TimeUnit.SECONDS));
            assertEquals(200, payment.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        String status = jdbc.queryForObject("SELECT status FROM order_cancellations WHERE order_id = ?", String.class, orderId);
        if ("APPLIED".equals(status)) {
            // cancelled first: the money that arrived afterwards is a reconciliation case, never applied
            assertEquals("CANCELLED", fulfillmentOf(orderId));
            assertEquals("CANCELLED", paymentOf(orderId));
            assertEquals(1, count("payment_reconciliation_cases"));
        } else {
            // paid first: the Kitchen's word does not cancel a paid order
            assertEquals("REJECTED", status);
            assertEquals("RECEIVED", fulfillmentOf(orderId));
            assertEquals("PAID", paymentOf(orderId));
            assertEquals(0, count("payment_reconciliation_cases"));
        }
        assertEquals(1, cancellationCount(orderId));
    }

    // ---- a request that is not valid is refused before anything is written ------------------------------------------

    @Test
    void theReasonIsRequiredAndNothingIsWrittenOrRememberedWithoutIt() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();

        kitchenAsks(askAll(eventId, orderId, null, null)).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of(), null)).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of(), "only a text")).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of("OTHER"), null)).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of("OTHER"), "  ")).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of("NOT_A_CODE"), null)).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, List.of("ITEM_UNAVAILABLE", "ITEM_UNAVAILABLE"), null))
                .andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, outOfStock(), "bell\u0007")).andExpect(status().isUnprocessableContent());
        kitchenAsks(askAll(eventId, orderId, outOfStock(), "x".repeat(301))).andExpect(status().isUnprocessableContent());

        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(0, count("order_cancellations"));
        assertEquals(0, count("inbox_events WHERE source = 'KITCHEN'"), "a refused request is not remembered");
        // the corrected request with the very same event id is accepted
        kitchenAsks(askAll(eventId, orderId, List.of("OTHER"), "ran out of everything")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
    }

    @Test
    void aBadSelectionIsRefusedWithoutARecord() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        UUID salad = lineOf(orderId, DevFixtures.SALAD);

        kitchenAsks(askItems(UUID.randomUUID(), orderId, salad, 4, outOfStock(), null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CANCEL_QUANTITY_EXCEEDED"));
        kitchenAsks(askItems(UUID.randomUUID(), orderId, UUID.randomUUID(), 1, outOfStock(), null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_ORDER_ITEM"));
        kitchenAsks(askItems(UUID.randomUUID(), orderId, salad, 0, outOfStock(), null))
                .andExpect(status().isUnprocessableContent());
        var both = askItems(UUID.randomUUID(), orderId, salad, 1, outOfStock(), null);
        both.put("cancelAll", true);
        kitchenAsks(both).andExpect(status().isUnprocessableContent());
        kitchenAsks(base(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isUnprocessableContent());

        assertEquals(0, count("order_cancellations"));
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
    }

    // ---- the Order Service refuses what its rules do not allow, and records the refusal ------------------------------

    @Test
    void aReadyOrderIsNotCancelledOnTheKitchensRequestAndTheRefusalIsRecorded() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");

        kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionCode").value("ORDER_ALREADY_PREPARED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("READY"));

        assertEquals("READY", fulfillmentOf(orderId));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, decision_source, decided_by_type, rejection_code,"
                + " cancelled_gross_minor, new_due_minor, previous_due_minor FROM order_cancellations");
        assertEquals("REJECTED", row.get("status"));
        assertEquals("SYSTEM", row.get("decision_source"));
        assertEquals("ORDER_ALREADY_PREPARED", row.get("rejection_code"));
        assertEquals(0L, row.get("cancelled_gross_minor"));
        assertEquals(row.get("previous_due_minor"), row.get("new_due_minor"));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items", Integer.class));
        assertEquals(List.of("OrderCancellationRejected"), eventTypes(orderId));
        assertEquals(List.of("ADMIN", "NOTIFICATION", "WAITER"), destinationsOf(orderId, "OrderCancellationRejected"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_type ="
                + " 'KitchenCancellationRequestRejected'", Integer.class));
        // the order stays open to the normal rules afterwards
        cancelAll(WAITER, orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));
    }

    @Test
    void aPaidOrderIsNeverCancelledOnTheKitchensWordAWaiterDecides() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        pay(orderId, "PAID", 5).andExpect(status().isOk());

        kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionCode").value("ORDER_ALREADY_PAID"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals("PAID", paymentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT sum(refund_required_minor) FROM order_cancellations", Long.class));

        // the waiter, who may, takes the decision (and the refund need is recorded as always)
        cancelAll(WAITER, orderId).andExpect(status().isCreated()).andExpect(jsonPath("$.amounts.refundRequiredMinor")
                .value(SALAD_DUE));
    }

    @Test
    void anAlreadyCancelledOrderIsAConflictAndLeavesNoSecondRecord() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
        assertEquals(1, cancellationCount(orderId));
        assertEquals(0, count("inbox_events WHERE source = 'KITCHEN'"));
    }

    @Test
    void whileAnotherRequestWaitsForTheKitchenTheKitchenAnswersThatOneInstead() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));

        kitchenAsks(askAll(UUID.randomUUID(), waiting.orderId(), outOfStock(), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_ALREADY_PENDING"))
                .andExpect(jsonPath("$.details.cancellationId").value(waiting.cancellationId().toString()));
        assertEquals(1, cancellationCount(waiting.orderId()));

        kitchenDecides(waiting.orderId(), waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));
    }

    // ---- redelivery ---------------------------------------------------------------------------------------------------

    @Test
    void aRedeliveredRequestDoesNothingTwiceAndTheSameIdWithAnotherBodyIsAConflict() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        UUID eventId = UUID.randomUUID();
        var body = askItems(eventId, orderId, lineOf(orderId, DevFixtures.SALAD), 1, outOfStock(), null);

        String first = kitchenAsks(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int events = count("outbox_events");
        kitchenAsks(body).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.cancellationId").value(json.readTree(first).path("cancellationId").asString()));

        assertEquals(events, count("outbox_events"));
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
        kitchenAsks(askItems(eventId, orderId, lineOf(orderId, DevFixtures.SALAD), 2, outOfStock(), null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        UUID other = placeSaladOrder(DevFixtures.TOKEN_A2);
        kitchenAsks(askAll(eventId, other, outOfStock(), null)).andExpect(status().isConflict());
        assertEquals("RECEIVED", fulfillmentOf(other));
    }

    @Test
    void aRedeliveryAfterTheIdempotencyRecordExpiredStillCancelsNothingTwice() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        var body = askItems(UUID.randomUUID(), orderId, lineOf(orderId, DevFixtures.SALAD), 1, outOfStock(), null);
        kitchenAsks(body).andExpect(status().isOk());

        jdbc.update("DELETE FROM idempotency_records WHERE operation = 'KITCHEN_CANCEL_REQUEST'");

        kitchenAsks(body).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("APPLIED")).andExpect(jsonPath("$.cancellationId").doesNotExist());
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
    }

    @Test
    void concurrentRedeliveriesCreateOneCancellation() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        var body = askItems(UUID.randomUUID(), orderId, lineOf(orderId, DevFixtures.SALAD), 1, outOfStock(), null);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return kitchenAsks(body).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) {
                assertEquals(200, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
    }

    @Test
    void theKitchensRequestAndTheCustomersCancellationRacingCancelTheOrderOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            var start = new CountDownLatch(1);
            var kitchen = pool.submit(() -> {
                start.await();
                return kitchenAsks(askAll(UUID.randomUUID(), orderId, outOfStock(), null)).andReturn().getResponse()
                        .getStatus();
            });
            var customer = pool.submit(() -> {
                start.await();
                return cancelAll(guest(A1), orderId).andReturn().getResponse().getStatus();
            });
            start.countDown();
            statuses.add(kitchen.get(30, TimeUnit.SECONDS));
            statuses.add(customer.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertTrue(statuses.equals(List.of(200, 409)) || statuses.equals(List.of(409, 201)), statuses.toString());
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
    }

    // ---- who may ask --------------------------------------------------------------------------------------------------

    @Test
    void onlyTheKitchenServiceMayAskAndOnlyForAnOrderOfTheRestaurantItNames() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var body = askAll(UUID.randomUUID(), orderId, outOfStock(), null);

        kitchenAsks(null, body).andExpect(status().isUnauthorized());
        kitchenAsks(service("not-a-credential"), body).andExpect(status().isUnauthorized());
        kitchenAsks(service(PAYMENT_CREDENTIAL), body).andExpect(status().isForbidden());
        kitchenAsks(staff(DevFixtures.STAFF_KITCHEN_A), body).andExpect(status().isForbidden());
        kitchenAsks(ADMIN, body).andExpect(status().isForbidden());
        kitchenAsks(WAITER, body).andExpect(status().isForbidden());
        kitchenAsks(guest(A1), body).andExpect(status().isForbidden());
        // a forged role or restaurant header opens nothing
        var forged = post(PATH).contentType("application/json").content(toJson(body)).header("X-Role", "KITCHEN")
                .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString());
        mvc.perform(forged).andExpect(status().isUnauthorized());
        // the right credential, the wrong restaurant
        var wrongRestaurant = askAll(UUID.randomUUID(), orderId, outOfStock(), null);
        wrongRestaurant.put("restaurantId", DevFixtures.RESTAURANT_B);
        kitchenAsks(wrongRestaurant).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        kitchenAsks(askAll(UUID.randomUUID(), UUID.randomUUID(), outOfStock(), null)).andExpect(status().isNotFound());
        // an unknown field is not an instruction
        var extra = askAll(UUID.randomUUID(), orderId, outOfStock(), null);
        extra.put("amountMinor", 1);
        kitchenAsks(extra).andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));

        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(0, count("order_cancellations"));
    }

    // ---- atomicity and the report --------------------------------------------------------------------------------------

    @Test
    void ifTheCancelledEventCannotBeStoredNothingIsCancelledAndTheRequestCanBeRetried() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var body = askAll(UUID.randomUUID(), orderId, outOfStock(), null);
        int outboxBefore = count("outbox_events");
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT ck_test_block_events"
                + " CHECK (event_type <> 'OrderCancelled') NOT VALID");
        try {
            kitchenAsks(body).andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertEquals("RECEIVED", fulfillmentOf(orderId));
            assertEquals(0, count("order_cancellations"));
            assertEquals(0, count("order_cancellation_reasons"));
            assertEquals(0, count("inbox_events WHERE source = 'KITCHEN'"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation ="
                    + " 'KITCHEN_CANCEL_REQUEST'", Integer.class));
            assertEquals(outboxBefore, count("outbox_events"));
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS ck_test_block_events");
        }
        kitchenAsks(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
    }

    @Test
    void theReportKeepsTheVerifiedServiceAndTheStaffMemberItClaimedApart() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID claimed = DevFixtures.USER_WAITER_A;
        var body = askAll(UUID.randomUUID(), orderId, List.of("KITCHEN_ERROR"), null);
        body.put("requestedByStaffId", claimed);
        kitchenAsks(body).andExpect(status().isOk());

        getWith(ADMIN, "/api/v1/admin/order-cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestedBy.type").value("SERVICE"))
                .andExpect(jsonPath("$.items[0].requestedBy.role").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].requestedBy.id").value("KITCHEN"))
                .andExpect(jsonPath("$.items[0].requestedBy.reportedStaffId").value(claimed.toString()))
                .andExpect(jsonPath("$.items[0].reasons[0].source").value("SERVICE"));
        // nobody else can read it: the report is ADMIN only, and the cancellation list hides the claim from the waiter
        getWith(WAITER, "/api/v1/admin/order-cancellations").andExpect(status().isForbidden());
        getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].requestedBy.id").doesNotExist())
                .andExpect(jsonPath("$.cancellations[0].requestedBy.reportedStaffId").doesNotExist());
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].requestedBy.reportedStaffId").doesNotExist());
        // the event names a kind and a role only
        assertFalse(payloadOf(orderId, "OrderCancelled").contains(claimed.toString()));
    }

    @Test
    void theAdministratorsReportShowsKitchenRequestsAsServiceRequests() throws Exception {
        UUID applied = placeSaladOrder(A1);
        kitchenAsks(askAll(UUID.randomUUID(), applied, List.of("ITEM_UNAVAILABLE"), null)).andExpect(status().isOk());
        UUID refused = placeSaladOrder(A1);
        advanceTo(refused, "READY");
        kitchenAsks(askAll(UUID.randomUUID(), refused, List.of("KITCHEN_ERROR"), null)).andExpect(status().isOk());

        getWith(ADMIN, "/api/v1/admin/order-cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].requestedBy.type", contains("SERVICE", "SERVICE")))
                .andExpect(jsonPath("$.summary.byStatus.APPLIED").value(1))
                .andExpect(jsonPath("$.summary.byStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.summary.byReason[?(@.code=='ITEM_UNAVAILABLE')].group").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.summary.withoutReasonCount").value(0));
        assertEquals(2, count("order_cancellations"));
    }
}
