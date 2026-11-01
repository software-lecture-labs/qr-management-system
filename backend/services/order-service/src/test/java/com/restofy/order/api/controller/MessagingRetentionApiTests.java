package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** K-11: inbox and outbox housekeeping: what is removed, what must never be, and what stays untouched. */
class MessagingRetentionApiTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;

    @Autowired
    private DataRetentionService retention;

    private void inbox(UUID orderId, Instant receivedAt) {
        jdbc.update("INSERT INTO inbox_events (id, source, event_id, restaurant_id, order_id, event_type, outcome,"
                        + " received_at) VALUES (?, 'KITCHEN', ?, ?, ?, 'KitchenOrderStatus', 'APPLIED', ?)",
                UUID.randomUUID(), UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, Timestamp.from(receivedAt));
    }

    /** An outbox event with one delivery per given status; deliveredAt applies to DELIVERED ones. */
    private UUID outbox(UUID orderId, Instant occurredAt, Instant deliveredAt, String... deliveryStatuses) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version,"
                        + " event_type, schema_version, payload, occurred_at) VALUES (?, ?, 'ORDER', ?, 1, 'TestEvent',"
                        + " 1, '{}'::jsonb, ?)", eventId, DevFixtures.RESTAURANT_A, orderId, Timestamp.from(occurredAt));
        String[] destinations = {"KITCHEN", "QR", "NOTIFICATION", "WAITER", "PAYMENT", "ADMIN"};
        for (int i = 0; i < deliveryStatuses.length; i++) {
            boolean delivered = deliveryStatuses[i].equals("DELIVERED");
            jdbc.update("INSERT INTO outbox_deliveries (id, event_id, destination, status, attempt_count, delivered_at)"
                            + " VALUES (?, ?, ?, ?, 1, ?)", UUID.randomUUID(), eventId, destinations[i],
                    deliveryStatuses[i], delivered && deliveredAt != null ? Timestamp.from(deliveredAt) : null);
        }
        return eventId;
    }

    private List<UUID> outboxIds(UUID orderId, String type) {
        return jdbc.queryForList("SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = ?", UUID.class,
                orderId, type);
    }

    private static Instant daysAgo(long days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    // ---- inbox ---------------------------------------------------------------------------------------------------------

    @Test
    void processedInboxEventsAreRemovedOnlyAfterTheDeduplicationWindow() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        inbox(orderId, daysAgo(91));
        inbox(orderId, daysAgo(200));
        inbox(orderId, daysAgo(89));
        inbox(orderId, Instant.now());

        assertEquals(2, retention.purgeProcessedInboxEvents());
        assertEquals(2, count("inbox_events"));
        assertEquals(0, retention.purgeProcessedInboxEvents(), "a second run finds nothing");
    }

    @Test
    void aRedeliveryIsStillRecognisedInsideTheWindowAndTheOrderAuditIsUntouched() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(eventId, DevFixtures.RESTAURANT_A, orderId, "PREPARING"))
                .andExpect(MockMvcResultMatchers.status().isOk());
        inbox(orderId, daysAgo(120));            // an ancient row elsewhere must not matter
        int historyRows = fulfillmentHistory(orderId).size();

        retention.runAll();

        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(eventId, DevFixtures.RESTAURANT_A, orderId, "PREPARING"))
                .andExpect(MockMvcResultMatchers
                        .jsonPath("$.duplicate").value(true));
        assertEquals(historyRows, fulfillmentHistory(orderId).size(), "history is never cleaned");
        assertEquals(1, count("orders"));
        assertEquals("PREPARING", fulfillmentOf(orderId));
    }

    // ---- outbox --------------------------------------------------------------------------------------------------------

    @Test
    void onlyEventsThatAreFullyDeliveredAndOldEnoughAreRemoved() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID oldDelivered = outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED", "DELIVERED");
        UUID recentlyDelivered = outbox(orderId, daysAgo(60), daysAgo(5), "DELIVERED", "DELIVERED");
        UUID oneStillPending = outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED", "PENDING");
        UUID oneFailed = outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED", "FAILED");
        UUID neverDelivered = outbox(orderId, daysAgo(400), null, "PENDING");
        UUID deliveredWithoutTime = outbox(orderId, daysAgo(60), null, "DELIVERED");
        UUID oneRecentOneOld = outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED");
        jdbc.update("INSERT INTO outbox_deliveries (id, event_id, destination, status, attempt_count, delivered_at)"
                + " VALUES (?, ?, 'WAITER', 'DELIVERED', 1, ?)", UUID.randomUUID(), oneRecentOneOld,
                Timestamp.from(daysAgo(2)));
        UUID noDeliveryRows = outbox(orderId, daysAgo(400), null);

        assertEquals(1, retention.purgeDeliveredOutboxEvents());

        List<UUID> remaining = outboxIds(orderId, "TestEvent");
        assertTrue(!remaining.contains(oldDelivered), "the fully delivered, old event is gone");
        for (UUID kept : List.of(recentlyDelivered, oneStillPending, oneFailed, neverDelivered, deliveredWithoutTime,
                oneRecentOneOld, noDeliveryRows)) {
            assertTrue(remaining.contains(kept), "must be kept: " + kept);
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE event_id = ?", Integer.class,
                oldDelivered), "its deliveries went with it");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE event_id = ?", Integer.class,
                oneStillPending), "the delivered half of a half-delivered event is kept with it");
    }

    @Test
    void theRealBusinessEventsAreNeverTreatedAsDelivered() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        pay(orderId, "PENDING", 100).andExpect(MockMvcResultMatchers
                .status().isOk());
        int events = count("outbox_events");
        jdbc.update("UPDATE outbox_events SET occurred_at = ?", Timestamp.from(daysAgo(500)));

        retention.runAll();

        assertEquals(events, count("outbox_events"), "without a dispatcher nothing is DELIVERED, so nothing is removed");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE status <> 'PENDING'",
                Integer.class));
    }

    @Test
    void cleaningRunsInBoundedBatches() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        for (int i = 0; i < 1_250; i++) {
            outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED");
        }
        // the default batch size is 500: three batches are needed and all of them run in one purge call
        assertEquals(1_250, retention.purgeDeliveredOutboxEvents());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'TestEvent'",
                Integer.class));
    }

    @Test
    void anAlreadyLockedRowIsSkippedNotWaitedFor() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = outbox(orderId, daysAgo(60), daysAgo(40), "DELIVERED");
        var tx = new TransactionTemplate(
                new JdbcTransactionManager(jdbc.getDataSource()));
        Integer deletedWhileLocked = tx.execute(status -> {
            jdbc.queryForList("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE", UUID.class, eventId);
            // another connection (the purge runs in its own transaction) must skip the locked row, not block
            return CompletableFuture.supplyAsync(retention::purgeDeliveredOutboxEvents).join();
        });
        assertEquals(0, deletedWhileLocked);
        assertEquals(1, retention.purgeDeliveredOutboxEvents(), "once it is free it is removed");
    }
    @Test
    void financialRecordsSurviveTheOrdinaryCleanupForEver() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(MockMvcResultMatchers
                .status().isOk());
        pay(orderId, "PAID", 60).andExpect(MockMvcResultMatchers
                .status().isOk());
        UUID firstCapture = captureOf(orderId);
        // a second collection: a reconciliation case (capture row + case row + outbox event)
        UUID secondCapture = UUID.randomUUID();
        payWith(orderId, "PAID", 30, attemptOf(orderId), secondCapture)
                .andExpect(MockMvcResultMatchers.status().isOk());
        // make everything ancient and mark every outbox delivery as delivered long ago
        jdbc.update("UPDATE inbox_events SET received_at = now() - interval '500 days'");
        jdbc.update("UPDATE outbox_events SET occurred_at = now() - interval '500 days'");
        jdbc.update("UPDATE outbox_deliveries SET status = 'DELIVERED', delivered_at = now() - interval '400 days'");

        retention.runAll();

        assertEquals(0, count("inbox_events"), "message bookkeeping is cleaned");
        assertEquals(2, count("payment_captures"), "the collections are financial records: never cleaned");
        assertEquals(1, count("payment_reconciliation_cases"), "and so is the reconciliation case");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type ="
                + " 'OrderPaymentReconciliationRequired'", Integer.class), "the reconciliation event is kept");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type <>"
                + " 'OrderPaymentReconciliationRequired'", Integer.class), "delivered ordinary announcements are cleaned");
        assertEquals(3, paymentHistory(orderId).size(), "the history is never touched");

        // every old message arrives again: no second financial effect and no second alert
        payWith(orderId, "PAID", 20, attemptOf(orderId), firstCapture)
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome")
                        .value("IGNORED_STALE"));
        payWith(orderId, "PAID", 10, attemptOf(orderId), secondCapture)
                .andExpect(MockMvcResultMatchers
                        .jsonPath("$.reconciliationRequired").value(true));
        assertEquals(1, count("payment_reconciliation_cases"));
        assertEquals(3, paymentHistory(orderId).size());
    }
}
