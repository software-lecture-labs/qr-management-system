package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Kitchen's answer to a cancellation request that waited for it, and what the Order Service does with it. */
class OrderCancellationDecisionApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    // ---- approval ---------------------------------------------------------------------------------------------------

    @Test
    void theKitchensApprovalCancelsTheWholeOrderAndFinalisesTheRequest() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID orderId = waiting.orderId();

        kitchenDecides(orderId, waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));

        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(4, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
        Map<String, Object> row = jdbc.queryForMap("SELECT decision_source, decided_by_type, decided_by_id,"
                + " new_due_minor, requested_by_type FROM order_cancellations WHERE id = ?", waiting.cancellationId());
        assertEquals("KITCHEN", row.get("decision_source"));
        assertEquals("SERVICE", row.get("decided_by_type"));
        assertEquals("KITCHEN", row.get("decided_by_id"));
        assertEquals(0L, row.get("new_due_minor"));
        assertEquals("GUEST", row.get("requested_by_type"), "who asked never changes");
        assertEquals(true, fulfillmentHistory(orderId).contains("PREPARING>CANCELLED|SERVICE|CANCELLATION"),
                fulfillmentHistory(orderId).toString());
        assertEquals(List.of("OrderAmountAdjusted", "OrderCancellationRequested", "OrderCancelled"),
                eventTypes(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE source = 'KITCHEN'"
                + " AND event_type = 'KitchenCancellationDecision'", Integer.class));
    }

    @Test
    void anApprovedPartialCancellationKeepsTheOrderInPreparationAndAddsUp() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID cancellationId = requestIdOf(cancel(guest(A1), orderId, newKey(),
                cancelItems(lineOf(orderId, DevFixtures.SALAD), 2)).andExpect(status().isCreated()));

        kitchenDecides(orderId, cancellationId, "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"));

        assertEquals(34_000L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId));
        assertEquals(2, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
        getWith(staff(DevFixtures.STAFF_WAITER_A), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.cancellations[0].amounts.cancelledGrossMinor").value(18_000))
                .andExpect(jsonPath("$.cancellations[0].amounts.newDueMinor").value(34_000))
                .andExpect(jsonPath("$.cancellations[0].decisionSource").value("KITCHEN"));
        assertEquals(true, eventTypes(orderId).contains("OrderItemsCancelled"));
    }

    @Test
    void anApprovalForAnOrderPaidByStaffRequestRecordsTheRefundItRequires() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        payAmount(orderId, "PENDING", MIXED_TOTAL).andExpect(status().isOk());
        payAmount(orderId, "PAID", MIXED_TOTAL).andExpect(status().isOk());
        advanceTo(orderId, "PREPARING");
        UUID cancellationId = requestIdOf(cancel(WAITER, orderId, newKey(), Map.of("cancelAll", true))
                .andExpect(status().isCreated()));

        kitchenDecides(orderId, cancellationId, "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));

        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT refund_required_minor FROM order_cancellations WHERE id = ?",
                Long.class, cancellationId));
        assertEquals(0L, jdbc.queryForObject("SELECT refunded_minor FROM order_cancellations WHERE id = ?", Long.class,
                cancellationId));
    }

    // ---- rejection --------------------------------------------------------------------------------------------------

    @Test
    void theKitchensRejectionChangesNothingAndFreesTheOrderForANewRequest() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID orderId = waiting.orderId();

        kitchenDecides(orderId, waiting.cancellationId(), "REJECTED", "ALREADY_COOKED").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.cancellationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"));

        assertEquals("PREPARING", fulfillmentOf(orderId));
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
        Map<String, Object> row = jdbc.queryForMap("SELECT rejection_code, decision_source, cancelled_gross_minor,"
                + " new_due_minor, previous_due_minor FROM order_cancellations WHERE id = ?", waiting.cancellationId());
        assertEquals("ALREADY_COOKED", row.get("rejection_code"));
        assertEquals("KITCHEN", row.get("decision_source"));
        assertEquals(0L, row.get("cancelled_gross_minor"), "a rejected request cancelled nothing");
        assertEquals(row.get("previous_due_minor"), row.get("new_due_minor"));
        // the Kitchen is not told about its own answer
        assertEquals(List.of("ADMIN", "NOTIFICATION", "WAITER"), destinationsOf(orderId, "OrderCancellationRejected"));
        assertEquals(List.of("OrderCancellationRejected", "OrderCancellationRequested"), eventTypes(orderId));

        // the slot is free again
        cancelAll(guest(A1), orderId).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void aRejectionNeedsACodeAndAnApprovalCarriesNone() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID orderId = waiting.orderId();

        kitchenDecides(orderId, waiting.cancellationId(), "REJECTED", null).andExpect(status().isUnprocessableContent());
        kitchenDecides(orderId, waiting.cancellationId(), "APPROVED", "KITCHEN_DECLINED")
                .andExpect(status().isUnprocessableContent());
        kitchenDecides(orderId, waiting.cancellationId(), "REJECTED", "lower case").andExpect(status().isUnprocessableContent());
        kitchenDecides(orderId, waiting.cancellationId(), "MAYBE", null).andExpect(status().isUnprocessableContent());
        assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
        assertEquals(0, count("inbox_events WHERE source = 'KITCHEN' AND event_type = 'KitchenCancellationDecision'"));
    }

    // ---- the Order Service does not take an approval on trust --------------------------------------------------------

    @Test
    void anApprovalThatArrivesAfterTheOrderIsReadyIsRejectedByTheOrderService() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID orderId = waiting.orderId();
        kitchenEventFor(orderId, "READY").andExpect(status().isOk());

        kitchenDecides(orderId, waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("READY"));

        assertEquals("READY", fulfillmentOf(orderId));
        Map<String, Object> row = jdbc.queryForMap("SELECT decision_source, decided_by_type, rejection_code"
                + " FROM order_cancellations WHERE id = ?", waiting.cancellationId());
        assertEquals("SYSTEM", row.get("decision_source"));
        assertEquals("SYSTEM", row.get("decided_by_type"));
        assertEquals("ORDER_ALREADY_PREPARED", row.get("rejection_code"));
        // here the Kitchen did not reject, so it hears about the rejection
        assertEquals(List.of("ADMIN", "KITCHEN", "NOTIFICATION", "WAITER"),
                destinationsOf(orderId, "OrderCancellationRejected"));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
    }

    @Test
    void aCustomerRequestIsNotFinalisedOnceTheOrderHasBeenPaid() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID orderId = waiting.orderId();
        payAmount(orderId, "PENDING", MIXED_TOTAL).andExpect(status().isOk());
        payAmount(orderId, "PAID", MIXED_TOTAL).andExpect(status().isOk());

        kitchenDecides(orderId, waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));

        assertEquals("ORDER_ALREADY_PAID", jdbc.queryForObject("SELECT rejection_code FROM order_cancellations"
                + " WHERE id = ?", String.class, waiting.cancellationId()));
        assertEquals("PREPARING", fulfillmentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT sum(refund_required_minor) FROM order_cancellations", Long.class));
    }

    // ---- redelivery and unknown requests ----------------------------------------------------------------------------

    @Test
    void aRedeliveredDecisionIsAcknowledgedAndNothingHappensTwice() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID eventId = UUID.randomUUID();
        var body = decisionBody(eventId, waiting.orderId(), waiting.cancellationId(), "APPROVED", null);

        kitchenDecision(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        int events = count("outbox_events");
        kitchenDecision(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));

        assertEquals(events, count("outbox_events"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ?"
                + " AND source = 'CANCELLATION' AND state_axis = 'FULFILLMENT'", Integer.class, waiting.orderId()));
    }

    @Test
    void aDecisionForAnAlreadyDecidedRequestIsIgnoredWhateverItSays() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        kitchenDecides(waiting.orderId(), waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk());

        kitchenDecides(waiting.orderId(), waiting.cancellationId(), "REJECTED", "KITCHEN_DECLINED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"))
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));
        assertEquals("APPLIED", cancellationStatus(waiting.cancellationId()), "a decision is final");
        assertEquals("CANCELLED", fulfillmentOf(waiting.orderId()));
    }

    @Test
    void anEventIdCannotBeReusedForAnotherOrder() throws Exception {
        Waiting first = waitingRequest(Map.of("cancelAll", true));
        UUID eventId = UUID.randomUUID();
        kitchenDecision(service(KITCHEN_CREDENTIAL), decisionBody(eventId, first.orderId(), first.cancellationId(),
                "REJECTED", "KITCHEN_DECLINED")).andExpect(status().isOk());
        UUID secondOrder = placeSaladOrder(DevFixtures.TOKEN_A2);

        kitchenDecision(service(KITCHEN_CREDENTIAL), decisionBody(eventId, secondOrder, UUID.randomUUID(), "APPROVED",
                null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void unknownOrdersAndForeignCancellationsAreNotFound() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID other = placeSaladOrder(DevFixtures.TOKEN_A2);

        // a cancellation of another order
        kitchenDecides(other, waiting.cancellationId(), "APPROVED", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_FOUND"));
        kitchenDecides(waiting.orderId(), UUID.randomUUID(), "APPROVED", null).andExpect(status().isNotFound());
        kitchenDecides(UUID.randomUUID(), waiting.cancellationId(), "APPROVED", null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        // the right order named under the wrong restaurant
        var wrongRestaurant = decisionBody(UUID.randomUUID(), waiting.orderId(), waiting.cancellationId(), "APPROVED",
                null);
        wrongRestaurant.put("restaurantId", DevFixtures.RESTAURANT_B);
        kitchenDecision(service(KITCHEN_CREDENTIAL), wrongRestaurant).andExpect(status().isNotFound());

        assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
        assertEquals(0, count("inbox_events WHERE source = 'KITCHEN' AND event_type = 'KitchenCancellationDecision'"),
                "a refused decision is not remembered, the Kitchen can retry");
    }

    // ---- who may decide --------------------------------------------------------------------------------------------

    @Test
    void onlyTheKitchenServiceMayDecide() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        var body = decisionBody(UUID.randomUUID(), waiting.orderId(), waiting.cancellationId(), "APPROVED", null);

        kitchenDecision(null, body).andExpect(status().isUnauthorized());
        kitchenDecision(service("not-a-credential"), body).andExpect(status().isUnauthorized());
        kitchenDecision(service(PAYMENT_CREDENTIAL), body).andExpect(status().isForbidden());
        kitchenDecision(staff(DevFixtures.STAFF_ADMIN_A), body).andExpect(status().isForbidden());
        kitchenDecision(staff(DevFixtures.STAFF_KITCHEN_A), body).andExpect(status().isForbidden());
        kitchenDecision(guest(A1), body).andExpect(status().isForbidden());

        assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
        assertEquals("PREPARING", fulfillmentOf(waiting.orderId()));
    }

    @Test
    void withoutTheKitchensAnswerTheRequestStaysOpenAndTheOrderUnchanged() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));

        // the order carries on: the Kitchen reports READY and the open request does not stop it
        kitchenEventFor(waiting.orderId(), "READY").andExpect(status().isOk());
        assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
        assertEquals("READY", fulfillmentOf(waiting.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, waiting.orderId()));
    }

    // ---- concurrency and atomicity ----------------------------------------------------------------------------------

    @Test
    void anApprovalRacingTheKitchensReadyLeavesAConsistentOrder() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var decision = pool.submit(() -> {
                start.await();
                return kitchenDecides(waiting.orderId(), waiting.cancellationId(), "APPROVED", null).andReturn()
                        .getResponse().getStatus();
            });
            var ready = pool.submit(() -> {
                start.await();
                return kitchenEventFor(waiting.orderId(), "READY").andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(200, decision.get(30, TimeUnit.SECONDS));
            assertEquals(200, ready.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        String order = fulfillmentOf(waiting.orderId());
        String request = cancellationStatus(waiting.cancellationId());
        if ("CANCELLED".equals(order)) {
            assertEquals("APPLIED", request);
        } else {
            assertEquals("READY", order);
            assertEquals("REJECTED", request);
        }
    }

    @Test
    void ifTheCancelledEventCannotBeStoredTheApprovalIsRolledBackAndCanBeRetried() throws Exception {
        Waiting waiting = waitingRequest(Map.of("cancelAll", true));
        UUID eventId = UUID.randomUUID();
        var body = decisionBody(eventId, waiting.orderId(), waiting.cancellationId(), "APPROVED", null);
        int outboxBefore = count("outbox_events");
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT ck_test_block_events"
                + " CHECK (event_type <> 'OrderCancelled') NOT VALID");
        try {
            kitchenDecision(service(KITCHEN_CREDENTIAL), body)
                    .andExpect(result -> assertEquals(true, result.getResponse().getStatus() >= 400));
            assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
            assertEquals("PREPARING", fulfillmentOf(waiting.orderId()));
            assertEquals(outboxBefore, count("outbox_events"));
            assertEquals(0, count("inbox_events WHERE source = 'KITCHEN' AND event_type = 'KitchenCancellationDecision'"));
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS ck_test_block_events");
        }
        kitchenDecision(service(KITCHEN_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));
    }
}
