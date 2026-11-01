package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 1: cancelling a whole order or chosen lines and units, with the fulfillment and payment rules. */
class OrderCancellationApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String KITCHEN = staff(DevFixtures.STAFF_KITCHEN_A);

    // ---- whole order ------------------------------------------------------------------------------------------------

    @Test
    void aGuestCancelsTheWholeOrderBeforeTheKitchenStarts() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancelAll(guest(A1), orderId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.decisionSource").value("AUTO"))
                .andExpect(jsonPath("$.kitchenConfirmationRequired").value(false))
                .andExpect(jsonPath("$.amounts.previousDueMinor").value(SALAD_DUE))
                .andExpect(jsonPath("$.amounts.cancelledGrossMinor").value(SALAD_DUE))
                .andExpect(jsonPath("$.amounts.newDueMinor").value(0))
                .andExpect(jsonPath("$.amounts.refundRequiredMinor").value(0))
                .andExpect(jsonPath("$.amounts.refundedMinor").value(0))
                .andExpect(jsonPath("$.requestedBy.type").value("GUEST"))
                .andExpect(jsonPath("$.requestedBy.id").doesNotExist())
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.order.paymentStatus").value("CANCELLED"));

        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertEquals("CANCELLED", paymentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(SALAD_DUE, jdbc.queryForObject("SELECT initial_gross_minor FROM orders WHERE id = ?", Long.class,
                orderId), "the original total is never rewritten");
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
        assertEquals(List.of("RECEIVED>CANCELLED|GUEST|CANCELLATION"),
                fulfillmentHistory(orderId).stream().filter(row -> row.contains("CANCELLED")).toList());
        assertEquals(List.of("NOT_REQUESTED>CANCELLED|SYSTEM|CANCELLATION"), paymentHistory(orderId).stream()
                .filter(row -> row.contains("CANCELLED")).toList());
        assertEquals(List.of("OrderAmountAdjusted", "OrderCancelled"), eventTypes(orderId));
        assertEquals(List.of("ADMIN", "KITCHEN", "NOTIFICATION", "PAYMENT", "QR", "WAITER"),
                destinationsOf(orderId, "OrderCancelled"));
        assertEquals(List.of("ADMIN", "PAYMENT"), destinationsOf(orderId, "OrderAmountAdjusted"));
    }

    @Test
    void theOrderDetailShowsCancelledAndRemainingUnitsAndMoney() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 2))
                .andExpect(status().isCreated());

        getWith(guest(A1), "/api/v1/orders/" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts.grossMinor").value(MIXED_TOTAL))
                .andExpect(jsonPath("$.cancelledGrossMinor").value(18_000))
                .andExpect(jsonPath("$.activeGrossMinor").value(34_000))
                .andExpect(jsonPath("$.currentDueMinor").value(34_000))
                .andExpect(jsonPath("$.items[?(@.name=='Dev Salad')].quantity").value(3))
                .andExpect(jsonPath("$.items[?(@.name=='Dev Salad')].cancelledQuantity").value(2))
                .andExpect(jsonPath("$.items[?(@.name=='Dev Salad')].remainingQuantity").value(1))
                .andExpect(jsonPath("$.items[?(@.name=='Dev Burger')].remainingQuantity").value(1));
        // the kitchen sees what is left to prepare, but no money
        getWith(staff(DevFixtures.STAFF_KITCHEN_A), "/api/v1/orders/" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='Dev Salad')].remainingQuantity").value(1))
                .andExpect(jsonPath("$.cancelledGrossMinor").doesNotExist())
                .andExpect(jsonPath("$.activeGrossMinor").doesNotExist())
                .andExpect(jsonPath("$.currentDueMinor").doesNotExist());
    }

    // ---- chosen lines and units -------------------------------------------------------------------------------------

    @Test
    void aPartialCancellationKeepsTheOrderAliveAndAddsUp() throws Exception {
        UUID orderId = placeMixedOrder(A1);

        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.scope").value("PARTIAL"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.amounts.cancelledGrossMinor").value(18_000))
                .andExpect(jsonPath("$.amounts.cancelledNetMinor").value(15_000))
                .andExpect(jsonPath("$.amounts.cancelledVatMinor").value(3_000))
                .andExpect(jsonPath("$.amounts.previousDueMinor").value(MIXED_TOTAL))
                .andExpect(jsonPath("$.amounts.newDueMinor").value(34_000))
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.order.paymentStatus").value("NOT_REQUESTED"));

        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(34_000L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId));
        assertEquals(List.of("OrderAmountAdjusted", "OrderItemsCancelled"), eventTypes(orderId));
        assertEquals(List.of("ADMIN", "KITCHEN", "NOTIFICATION", "WAITER"),
                destinationsOf(orderId, "OrderItemsCancelled"));
        assertEquals(List.of(), fulfillmentHistory(orderId).stream().filter(row -> row.contains("CANCELLED")).toList(),
                "a partial cancellation is not a status change");
    }

    @Test
    void cancellingTheRestStepByStepEndsWithACancelledOrderAndExactMoney() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        UUID salad = lineOf(orderId, DevFixtures.SALAD);
        UUID burger = lineOf(orderId, DevFixtures.BURGER);

        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("PARTIAL"));
        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 2, burger, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.amounts.newDueMinor").value(0))
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("CANCELLED"));

        // every cent of the original order is accounted for, nothing more and nothing less
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT sum(cancelled_gross_minor) FROM order_cancellations"
                + " WHERE order_id = ?", Long.class, orderId));
        assertEquals(jdbc.queryForObject("SELECT initial_net_minor FROM orders WHERE id = ?", Long.class, orderId),
                jdbc.queryForObject("SELECT sum(cancelled_net_minor) FROM order_cancellations WHERE order_id = ?",
                        Long.class, orderId));
        assertEquals(jdbc.queryForObject("SELECT initial_vat_minor FROM orders WHERE id = ?", Long.class, orderId),
                jdbc.queryForObject("SELECT sum(cancelled_vat_minor) FROM order_cancellations WHERE order_id = ?",
                        Long.class, orderId));
        // nothing is left to cancel
        cancelAll(guest(A1), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
    }

    @Test
    void cancelAllAfterAPartialCancellationTakesOnlyWhatIsStillActive() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated());

        cancelAll(guest(A1), orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.amounts.previousDueMinor").value(27_000))
                .andExpect(jsonPath("$.amounts.cancelledGrossMinor").value(27_000))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void badSelectionsAreRefusedAndChangeNothing() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        UUID salad = lineOf(orderId, DevFixtures.SALAD);

        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 4)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CANCEL_QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.details.remainingQuantity").value(3));
        cancel(guest(A1), orderId, newKey(), cancelItems(UUID.randomUUID(), 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ORDER_ITEM"));
        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 0)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        cancel(guest(A1), orderId, newKey(), cancelItems(salad, -1)).andExpect(status().isUnprocessableContent());
        cancel(guest(A1), orderId, newKey(), cancelItems(salad, 1, salad, 1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        cancel(guest(A1), orderId, newKey(), Map.of()).andExpect(status().isUnprocessableContent());
        cancel(guest(A1), orderId, newKey(), Map.of("cancelAll", false)).andExpect(status().isUnprocessableContent());
        cancel(guest(A1), orderId, newKey(), Map.of("cancelAll", true, "items",
                List.of(Map.of("orderItemId", salad, "quantity", 1)))).andExpect(status().isUnprocessableContent());

        assertEquals(0, cancellationCount(orderId));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId));
    }

    @Test
    void aLineOfAnotherOrderIsNotAcceptedEvenWithAValidId() throws Exception {
        UUID mine = placeMixedOrder(A1);
        UUID other = placeSaladOrder(DevFixtures.TOKEN_A2);

        cancel(guest(A1), mine, newKey(), cancelItems(lineOf(other, DevFixtures.SALAD), 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ORDER_ITEM"));
        assertEquals(0, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, other));
    }

    // ---- fulfillment rules ------------------------------------------------------------------------------------------

    @Test
    void anOrderInPreparationOnlyGetsARequestAndChangesNothing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "PREPARING");

        cancelAll(guest(A1), orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.kitchenConfirmationRequired").value(true))
                .andExpect(jsonPath("$.decisionSource").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").doesNotExist())
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("PREPARING"));

        assertEquals("PREPARING", fulfillmentOf(orderId));
        assertEquals(SALAD_DUE, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId));
        assertEquals(0, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, orderId), "nothing is cancelled until the kitchen confirms");
        assertEquals(List.of("OrderCancellationRequested"), eventTypes(orderId));
        assertEquals(List.of("ADMIN", "KITCHEN", "NOTIFICATION", "WAITER"),
                destinationsOf(orderId, "OrderCancellationRequested"));

        // while it waits, no second request can be started
        cancelAll(guest(A1), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_ALREADY_PENDING"));
        cancelAll(WAITER, orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_ALREADY_PENDING"));
        assertEquals(1, cancellationCount(orderId));
    }

    @Test
    void aReadyOrCompletedOrderCannotBeCancelledByAnyone() throws Exception {
        UUID ready = placeSaladOrder(A1);
        advanceTo(ready, "READY");
        for (String caller : List.of(guest(A1), WAITER, ADMIN)) {
            cancelAll(caller, ready).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.details.reason").value("ORDER_ALREADY_PREPARED"));
        }
        UUID done = placeSaladOrder(A1);
        advanceTo(done, "COMPLETED");
        cancelAll(ADMIN, done).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));

        assertEquals(0, count("order_cancellations"));
        assertEquals("READY", fulfillmentOf(ready));
    }

    @Test
    void d02ReadyServedAndCompletedAreRefusedTheSameWayForEveryoneAndLeaveNothingBehind() throws Exception {
        for (String status : List.of("READY", "SERVED", "COMPLETED")) {
            UUID orderId = placeSaladOrder(A1);
            pay(orderId, "PENDING", 10).andExpect(status().isOk());
            pay(orderId, "PAID", 5).andExpect(status().isOk());
            advanceTo(orderId, status);
            int events = count("outbox_events");
            int history = fulfillmentHistory(orderId).size();
            for (String caller : List.of(guest(A1), WAITER, ADMIN)) {
                cancelAll(caller, orderId).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"))
                        .andExpect(jsonPath("$.details.reason").value("ORDER_ALREADY_PREPARED"))
                        .andExpect(jsonPath("$.details.fulfillmentStatus").value(status))
                        .andExpect(jsonPath("$.details.paymentStatus").value("PAID"));
                cancel(caller, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));
            }
            assertEquals(status, fulfillmentOf(orderId));
            assertEquals("PAID", paymentOf(orderId));
            assertEquals(events, count("outbox_events"), "a refused cancellation announces nothing");
            assertEquals(history, fulfillmentHistory(orderId).size(), "the history is untouched");
            assertEquals(0, cancellationCount(orderId), "no cancellation record, no refund need");
        }
    }

    @Test
    void anAlreadyCancelledOrderIsRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        cancelAll(guest(A1), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
        cancelAll(ADMIN, orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
        assertEquals(1, cancellationCount(orderId));
    }

    // ---- payment rules ----------------------------------------------------------------------------------------------

    @Test
    void aGuestCannotCancelAPaidOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        pay(orderId, "PAID", 5).andExpect(status().isOk());

        cancelAll(guest(A1), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.details.reason").value("ORDER_ALREADY_PAID"));
        assertEquals(0, count("order_cancellations"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    @Test
    void aWaiterCancellingAPaidOrderRecordsTheRefundItRequiresButNeverARefund() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        pay(orderId, "PAID", 5).andExpect(status().isOk());

        cancelAll(WAITER, orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.requestedBy.type").value("STAFF"))
                .andExpect(jsonPath("$.requestedBy.role").value("WAITER"))
                .andExpect(jsonPath("$.requestedBy.id").doesNotExist())
                .andExpect(jsonPath("$.amounts.collectedMinor").value(SALAD_DUE))
                .andExpect(jsonPath("$.amounts.refundRequiredMinor").value(SALAD_DUE))
                .andExpect(jsonPath("$.amounts.refundedMinor").value(0))
                .andExpect(jsonPath("$.amounts.newDueMinor").value(0))
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.order.paymentStatus").value("PAID"));

        assertEquals("PAID", paymentOf(orderId), "the verified collection stays recorded");
        assertEquals(0L, jdbc.queryForObject("SELECT sum(refunded_minor) FROM order_cancellations", Long.class));
        String adjusted = payloadOf(orderId, "OrderAmountAdjusted");
        assertEquals(true, adjusted.contains("\"refundRequiredMinor\": 9000"), adjusted);
        String cancelled = payloadOf(orderId, "OrderCancelled");
        assertEquals(true, cancelled.contains("\"refundReviewRequired\": true"), cancelled);
    }

    @Test
    void partialCancellationsOfAPaidOrderRequireOnlyTheDifferenceEachTime() throws Exception {
        UUID orderId = placeAndPayMixedOrder();

        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.refundRequiredMinor").value(25_000))
                .andExpect(jsonPath("$.amounts.newDueMinor").value(0))
                .andExpect(jsonPath("$.order.fulfillmentStatus").value("RECEIVED"));
        cancel(ADMIN, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.refundRequiredMinor").value(9_000));
        cancelAll(WAITER, orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.refundRequiredMinor").value(18_000));

        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT sum(refund_required_minor) FROM order_cancellations"
                + " WHERE order_id = ?", Long.class, orderId), "in total exactly what was collected is to be refunded");
    }

    @Test
    void aPendingPaymentIsClosedWhenTheWholeOrderIsCancelledAndALateCollectionIsReconciled() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());

        cancelAll(guest(A1), orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.paymentStatus").value("CANCELLED"));
        assertEquals(List.of("NOT_REQUESTED>PENDING|SERVICE|PAYMENT_EVENT", "PENDING>CANCELLED|SYSTEM|CANCELLATION"),
                paymentHistory(orderId).stream().filter(row -> !row.startsWith("-")).toList());

        // the guest was already at the payment page: the money arrives for a cancelled order and is never applied
        pay(orderId, "PAID", 1).andExpect(status().isOk());
        assertEquals("CANCELLED", paymentOf(orderId));
        assertEquals(1, count("payment_reconciliation_cases"));
        assertEquals("ORDER_CANCELLED", jdbc.queryForObject("SELECT reason FROM payment_reconciliation_cases",
                String.class));
        assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT status FROM payment_captures", String.class));
    }

    @Test
    void aCollectionThatWasWaitingForItsAttemptIsReconciledWhenTheOrderIsCancelled() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        // PAID arrived before the PENDING of its attempt: held, not trusted, not lost
        pay(orderId, "PAID", 5).andExpect(status().isOk());
        assertEquals("PENDING_VERIFICATION", jdbc.queryForObject("SELECT status FROM payment_captures", String.class));

        cancelAll(guest(A1), orderId).andExpect(status().isCreated());

        assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT status FROM payment_captures", String.class));
        assertEquals("ORDER_CANCELLED", jdbc.queryForObject("SELECT not_applied_reason FROM payment_captures",
                String.class));
        assertEquals(1, count("payment_reconciliation_cases"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderPaymentReconciliationRequired'", Integer.class, orderId));
    }

    @Test
    void aPartialCancellationMakesAnOldPaymentAmountAMismatch() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        payAmount(orderId, "PENDING", MIXED_TOTAL).andExpect(status().isOk());
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amounts.newDueMinor").value(27_000))
                .andExpect(jsonPath("$.order.paymentStatus").value("PENDING"));

        // the old amount no longer settles the order; the new one does
        payAmount(orderId, "PAID", MIXED_TOTAL).andExpect(status().isOk());
        assertEquals("PENDING", paymentOf(orderId));
        assertEquals(1, count("payment_reconciliation_cases"));
    }

    // ---- who may cancel ---------------------------------------------------------------------------------------------

    @Test
    void onlyTheOwnerGuestAndTheRestaurantsWaiterOrAdminMayCancel() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        cancelAll(guest(DevFixtures.TOKEN_A2), orderId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        cancelAll(guest(DevFixtures.TOKEN_B1), orderId).andExpect(status().isNotFound());
        cancelAll(staff(DevFixtures.STAFF_WAITER_B), orderId).andExpect(status().isNotFound());
        cancelAll(staff(DevFixtures.STAFF_ADMIN_B), orderId).andExpect(status().isNotFound());
        cancelAll(KITCHEN, orderId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        cancelAll(service(KITCHEN_CREDENTIAL), orderId).andExpect(status().isForbidden());
        cancelAll(null, orderId).andExpect(status().isUnauthorized());
        cancelAll(guest("not-a-session"), orderId).andExpect(status().isUnauthorized());
        assertEquals(0, count("order_cancellations"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));

        cancelAll(ADMIN, orderId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedBy.role").value("ADMIN"))
                .andExpect(jsonPath("$.requestedBy.id").value(DevFixtures.USER_ADMIN_A.toString()));
    }

    @Test
    void aGuestCannotCancelOnceTheTableSessionHasClosedButStaffCan() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        qr.register(A1, DevFixtures.guestA1(SessionState.CLOSED));

        cancelAll(guest(A1), orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
        // staff are not tied to the guest's session
        cancelAll(WAITER, orderId).andExpect(status().isCreated());
    }

    // ---- idempotency ------------------------------------------------------------------------------------------------

    @Test
    void retryingTheSameRequestReturnsTheSameCancellation() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        String key = newKey();
        Map<String, Object> body = cancelItems(lineOf(orderId, DevFixtures.SALAD), 1);

        String first = cancel(guest(A1), orderId, key, body).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn().getResponse().getContentAsString();
        String second = cancel(guest(A1), orderId, key, body).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse()
                .getContentAsString();

        assertEquals(json.readTree(first).path("cancellationId").asString(),
                json.readTree(second).path("cancellationId").asString());
        assertEquals(1, cancellationCount(orderId), "cancelled once, not twice");
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
        assertEquals(2, eventTypes(orderId).size());
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflictAndNothingHappens() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        String key = newKey();
        UUID salad = lineOf(orderId, DevFixtures.SALAD);
        cancel(guest(A1), orderId, key, cancelItems(salad, 1)).andExpect(status().isCreated());

        cancel(guest(A1), orderId, key, cancelItems(salad, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        cancel(guest(A1), orderId, key, cancelAllWith(List.of("OTHER"), null)).andExpect(status().isConflict());
        assertEquals(1, cancellationCount(orderId));
    }

    @Test
    void theKeyIsScopedToTheCallerAndTheOrder() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(A1);
        String key = newKey();
        cancel(guest(A1), first, key, Map.of("cancelAll", true)).andExpect(status().isCreated());

        // the same key on another order is another request with another body: refused, the second order is untouched
        cancel(guest(A1), second, key, Map.of("cancelAll", true)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals("RECEIVED", fulfillmentOf(second));
        // another caller with the same key is a different scope
        cancel(WAITER, second, key, Map.of("cancelAll", true)).andExpect(status().isCreated());
    }

    @Test
    void aMissingOrMalformedKeyIsRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancel(guest(A1), orderId, null, Map.of("cancelAll", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        cancel(guest(A1), orderId, "short", Map.of("cancelAll", true)).andExpect(status().isBadRequest());
        cancel(guest(A1), orderId, "has spaces in it!", Map.of("cancelAll", true)).andExpect(status().isBadRequest());
        assertEquals(0, count("order_cancellations"));
    }

    @Test
    void aFailedAttemptDoesNotBurnTheKey() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        String key = newKey();
        cancel(guest(A1), orderId, key, cancelItems(lineOf(orderId, DevFixtures.SALAD), 9))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation = 'CANCEL_ORDER'",
                Integer.class), "a refused request does not burn its key");

        cancel(guest(A1), orderId, key, cancelItems(lineOf(orderId, DevFixtures.SALAD), 1))
                .andExpect(status().isCreated());
    }

    // ---- concurrency ------------------------------------------------------------------------------------------------

    @Test
    void twoConcurrentCancellationsOfTheWholeOrderCancelItOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String caller : List.of(guest(A1), WAITER)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cancelAll(caller, orderId).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            statuses.sort(Integer::compare);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE order_id = ?",
                Integer.class, orderId));
        assertEquals(1, eventTypes(orderId).stream().filter("OrderCancelled"::equals).count());
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneCancellation() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        String key = newKey();
        Map<String, Object> body = cancelItems(lineOf(orderId, DevFixtures.SALAD), 1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cancel(guest(A1), orderId, key, body).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var result : results) {
                assertEquals(201, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, cancellationCount(orderId));
        assertEquals(1, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, orderId));
    }

    @Test
    void aPaymentAndACancellationRacingLeaveAConsistentOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 10).andExpect(status().isOk());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var paid = pool.submit(() -> {
                start.await();
                return pay(orderId, "PAID", 1).andReturn().getResponse().getStatus();
            });
            var cancelled = pool.submit(() -> {
                start.await();
                return cancelAll(WAITER, orderId).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(200, paid.get(30, TimeUnit.SECONDS));
            assertEquals(201, cancelled.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // whichever came first, money and cancellation agree: either the refund is required, or the late money is a case
        assertEquals("CANCELLED", fulfillmentOf(orderId));
        long refundRequired = jdbc.queryForObject("SELECT refund_required_minor FROM order_cancellations", Long.class);
        int cases = count("payment_reconciliation_cases");
        if ("PAID".equals(paymentOf(orderId))) {
            assertEquals(SALAD_DUE, refundRequired);
            assertEquals(0, cases);
        } else {
            assertEquals("CANCELLED", paymentOf(orderId));
            assertEquals(0L, refundRequired);
            assertEquals(1, cases);
        }
    }
}
