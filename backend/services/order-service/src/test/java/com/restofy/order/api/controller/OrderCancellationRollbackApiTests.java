package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cancellation is all-or-nothing: the cancellation, its lines and reasons, the order change, the history, the
 * released payment evidence and every outbox event are one transaction. The failures here are real database failures
 * (the outbox table refuses one event type for the duration of a test), not mocks.
 */
class OrderCancellationRollbackApiTests extends AbstractCancellationApiTest {

    private void blockEvent(String eventType) {
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT ck_test_block_events"
                + " CHECK (event_type <> '" + eventType + "') NOT VALID");
    }

    private void unblockEvent() {
        jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS ck_test_block_events");
    }

    private void assertNothingChanged(UUID orderId, String fulfillment, String payment, long due, int outboxBefore) {
        assertEquals(fulfillment, fulfillmentOf(orderId));
        assertEquals(payment, paymentOf(orderId));
        assertEquals(due, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT coalesce(sum(cancelled_quantity), 0) FROM order_items"
                + " WHERE order_id = ?", Integer.class, orderId));
        assertEquals(0, count("order_cancellations"));
        assertEquals(0, count("order_cancellation_items"));
        assertEquals(0, count("order_cancellation_reasons"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation = 'CANCEL_ORDER'",
                Integer.class), "a failed attempt does not burn its key");
        assertEquals(0, fulfillmentHistory(orderId).stream().filter(row -> row.contains("CANCELLED")).count());
        assertEquals(outboxBefore, count("outbox_events"), "no event of a cancellation that did not happen");
    }

    @Test
    void ifTheCancelledEventCannotBeStoredNothingIsCancelled() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        int outboxBefore = count("outbox_events");
        String key = newKey();
        blockEvent("OrderCancelled");
        try {
            cancel(guest(A1), orderId, key, Map.of("cancelAll", true, "reasonCodes", List.of("OTHER")))
                    .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertNothingChanged(orderId, "RECEIVED", "NOT_REQUESTED", SALAD_DUE, outboxBefore);
        } finally {
            unblockEvent();
        }
        // the fault is gone: the very same request now succeeds
        cancel(guest(A1), orderId, key, Map.of("cancelAll", true, "reasonCodes", List.of("OTHER")))
                .andExpect(status().isCreated());
        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertEquals(1, cancellationCount(orderId));
    }

    @Test
    void ifTheLastEventFailsTheEarlierOnesAreRolledBackToo() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        int outboxBefore = count("outbox_events");
        blockEvent("OrderAmountAdjusted");
        try {
            cancelAll(guest(A1), orderId).andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertNothingChanged(orderId, "RECEIVED", "NOT_REQUESTED", SALAD_DUE, outboxBefore);
            assertEquals(0, eventTypes(orderId).size(), "not even the OrderCancelled that was written first");
        } finally {
            unblockEvent();
        }
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());
    }

    @Test
    void ifThePartialEventCannotBeStoredTheUnitsAreNotCancelled() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        int outboxBefore = count("outbox_events");
        blockEvent("OrderItemsCancelled");
        try {
            cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 2))
                    .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertNothingChanged(orderId, "RECEIVED", "NOT_REQUESTED", MIXED_TOTAL, outboxBefore);
        } finally {
            unblockEvent();
        }
    }

    @Test
    void ifTheRequestEventCannotBeStoredNoRequestIsKept() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "PREPARING");
        int outboxBefore = count("outbox_events");
        blockEvent("OrderCancellationRequested");
        try {
            cancelAll(guest(A1), orderId).andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertNothingChanged(orderId, "PREPARING", "NOT_REQUESTED", SALAD_DUE, outboxBefore);
        } finally {
            unblockEvent();
        }
        // and the order is not stuck behind a request that was never stored
        cancelAll(guest(A1), orderId).andExpect(status().isCreated());
    }

    @Test
    void ifTheReconciliationEventFailsTheWaitingCollectionStaysWaiting() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PAID", 5).andExpect(status().isOk());
        int outboxBefore = count("outbox_events");
        blockEvent("OrderPaymentReconciliationRequired");
        try {
            cancelAll(guest(A1), orderId).andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
            assertNothingChanged(orderId, "RECEIVED", "NOT_REQUESTED", SALAD_DUE, outboxBefore);
            assertEquals("PENDING_VERIFICATION", jdbc.queryForObject("SELECT status FROM payment_captures", String.class),
                    "money that was reported is neither dropped nor settled by a failed cancellation");
            assertEquals(0, count("payment_reconciliation_cases"));
        } finally {
            unblockEvent();
        }
    }
}
