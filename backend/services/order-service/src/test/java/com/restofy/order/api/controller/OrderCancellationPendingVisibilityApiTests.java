package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KARAR 04: a partial cancellation of an order in preparation needs the Kitchen's answer and is never finalised without
 * it; a request that waits is kept VISIBLE to the administrator (age, overdue) but nothing is decided because time passed.
 */
class OrderCancellationPendingVisibilityApiTests extends AbstractCancellationApiTest {

    private static final String REPORT = "/api/v1/admin/order-cancellations";
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);

    @Autowired
    private DataRetentionService retention;

    /** Test-only: moves the time of a request into the past (the production trigger forbids rewriting it). */
    private void waitedSince(UUID cancellationId, Instant requestedAt) {
        jdbc.execute("ALTER TABLE order_cancellations DISABLE TRIGGER trg_order_cancellations_guard");
        try {
            jdbc.update("UPDATE order_cancellations SET requested_at = ? WHERE id = ?", Timestamp.from(requestedAt),
                    cancellationId);
        } finally {
            jdbc.execute("ALTER TABLE order_cancellations ENABLE TRIGGER trg_order_cancellations_guard");
        }
    }

    private Waiting waitingPartial() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        advanceTo(orderId, "PREPARING");
        UUID cancellationId = requestIdOf(cancel(guest(A1), orderId, newKey(),
                cancelItems(lineOf(orderId, DevFixtures.SALAD), 2)).andExpect(status().isCreated()));
        return new Waiting(orderId, cancellationId);
    }

    // ---- a partial cancellation in preparation waits for the Kitchen ----------------------------------------------------

    @Test
    void aPartialRequestInPreparationChangesNothingUntilTheKitchenAnswers() throws Exception {
        Waiting waiting = waitingPartial();
        waitedSince(waiting.cancellationId(), Instant.now().minus(3, ChronoUnit.DAYS));
        int events = count("outbox_events");
        int history = fulfillmentHistory(waiting.orderId()).size();

        // time, the retention jobs, reading the report and every other caller change nothing
        retention.runAll();
        getWith(ADMIN, REPORT).andExpect(status().isOk());
        getWith(WAITER, "/api/v1/orders/" + waiting.orderId() + "/cancellations").andExpect(status().isOk());
        cancel(WAITER, waiting.orderId(), newKey(), cancelItems(lineOf(waiting.orderId(), DevFixtures.SALAD), 1))
                .andExpect(status().isConflict());
        kitchenDecision(staff(DevFixtures.STAFF_KITCHEN_A), decisionBody(UUID.randomUUID(), waiting.orderId(),
                waiting.cancellationId(), "APPROVED", null)).andExpect(status().isForbidden());
        kitchenDecision(ADMIN, decisionBody(UUID.randomUUID(), waiting.orderId(), waiting.cancellationId(), "APPROVED",
                null)).andExpect(status().isForbidden());

        assertEquals("REQUESTED", cancellationStatus(waiting.cancellationId()));
        assertEquals("PREPARING", fulfillmentOf(waiting.orderId()));
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                waiting.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, waiting.orderId()));
        assertEquals(events, count("outbox_events"));
        assertEquals(history, fulfillmentHistory(waiting.orderId()).size());
    }

    @Test
    void theKitchensApprovalOfAPartialRequestCancelsOnlyWhatWasAskedAndKeepsTheRest() throws Exception {
        Waiting waiting = waitingPartial();

        kitchenDecides(waiting.orderId(), waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("APPLIED"));

        assertEquals(2, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.SALAD, waiting.orderId()));
        assertEquals(0, jdbc.queryForObject("SELECT cancelled_quantity FROM order_items WHERE product_id = ?"
                + " AND order_id = ?", Integer.class, DevFixtures.BURGER, waiting.orderId()), "the other lines stay");
        assertEquals(34_000L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                waiting.orderId()));
        assertEquals(MIXED_TOTAL, jdbc.queryForObject("SELECT initial_gross_minor FROM orders WHERE id = ?", Long.class,
                waiting.orderId()), "the original snapshot is untouched");
        assertEquals("PREPARING", fulfillmentOf(waiting.orderId()));
    }

    @Test
    void aPartialRequestApprovedAfterTheOrderWasPaidByTheCustomerIsRejectedNotApplied() throws Exception {
        Waiting waiting = waitingPartial();
        payAmount(waiting.orderId(), "PENDING", MIXED_TOTAL).andExpect(status().isOk());
        payAmount(waiting.orderId(), "PAID", MIXED_TOTAL).andExpect(status().isOk());

        kitchenDecides(waiting.orderId(), waiting.cancellationId(), "APPROVED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationStatus").value("REJECTED"));

        assertEquals("ORDER_ALREADY_PAID", jdbc.queryForObject("SELECT rejection_code FROM order_cancellations WHERE id = ?",
                String.class, waiting.cancellationId()));
        assertEquals(0, jdbc.queryForObject("SELECT sum(cancelled_quantity) FROM order_items WHERE order_id = ?",
                Integer.class, waiting.orderId()));
        assertEquals("PAID", paymentOf(waiting.orderId()));
    }

    // ---- visibility for the administrator ----------------------------------------------------------------------------

    @Test
    void aWaitingRequestShowsItsAgeAndBecomesOverdueAfterTheThreshold() throws Exception {
        Waiting fresh = waitingPartial();
        Waiting old = waitingPartial();
        waitedSince(old.cancellationId(), Instant.now().minus(2, ChronoUnit.HOURS));

        JsonNode report = body(getWith(ADMIN, REPORT + "?status=REQUESTED&sort=requestedAt,asc").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.summary.pending.attentionAfterSeconds").value(900))
                .andExpect(jsonPath("$.summary.pending.overdueCount").value(1)));
        // the one that waits longest comes first
        assertEquals(old.cancellationId().toString(), report.path("items").get(0).path("cancellationId").asString());
        assertTrue(report.path("items").get(0).path("overdue").asBoolean());
        assertTrue(report.path("items").get(0).path("pendingAgeSeconds").asLong() >= 7_200);
        assertEquals(fresh.cancellationId().toString(), report.path("items").get(1).path("cancellationId").asString());
        assertEquals(false, report.path("items").get(1).path("overdue").asBoolean());
        assertTrue(report.path("items").get(1).path("pendingAgeSeconds").asLong() < 900);
        assertEquals(old.orderId().toString(), report.path("items").get(0).path("orderId").asString());
        assertTrue(Instant.parse(report.path("summary").path("pending").path("oldestRequestedAt").asString())
                .isBefore(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    @Test
    void theOverdueFilterListsOnlyTheRequestsThatWaitedTooLong() throws Exception {
        Waiting fresh = waitingPartial();
        Waiting old = waitingPartial();
        waitedSince(old.cancellationId(), Instant.now().minus(1, ChronoUnit.DAYS));
        // a decided cancellation is never "overdue", however old
        UUID decided = placeSaladOrder(A1);
        cancelAll(guest(A1), decided).andExpect(status().isCreated());

        getWith(ADMIN, REPORT + "?overdue=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].cancellationId").value(old.cancellationId().toString()))
                .andExpect(jsonPath("$.summary.pending.overdueCount").value(1))
                .andExpect(jsonPath("$.summary.byStatus.REQUESTED").value(1));
        getWith(ADMIN, REPORT + "?overdue=true&status=REQUESTED").andExpect(jsonPath("$.totalItems").value(1));
        getWith(ADMIN, REPORT + "?overdue=false").andExpect(jsonPath("$.totalItems").value(3));
        getWith(ADMIN, REPORT + "?overdue=true&status=APPLIED").andExpect(status().isUnprocessableContent());
        getWith(ADMIN, REPORT + "?sort=oldest").andExpect(status().isUnprocessableContent());
        // decided rows carry no age
        getWith(ADMIN, REPORT + "?status=APPLIED").andExpect(jsonPath("$.items[0].pendingAgeSeconds").doesNotExist())
                .andExpect(jsonPath("$.items[0].overdue").value(false))
                .andExpect(jsonPath("$.summary.pending.overdueCount").value(0));
        Assertions.assertNotNull(fresh.cancellationId());
    }

    @Test
    void theOverdueViewIsAdminOnlyAndOfTheAdministratorsRestaurantOnly() throws Exception {
        Waiting old = waitingPartial();
        waitedSince(old.cancellationId(), Instant.now().minus(1, ChronoUnit.DAYS));

        getWith(WAITER, REPORT + "?overdue=true").andExpect(status().isForbidden());
        getWith(guest(A1), REPORT + "?overdue=true").andExpect(status().isForbidden());
        getWith(staff(DevFixtures.STAFF_ADMIN_B), REPORT + "?overdue=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.summary.pending.overdueCount").value(0));
    }

    @Test
    void afterTheKitchenAnswersTheRequestIsNoLongerPending() throws Exception {
        Waiting old = waitingPartial();
        waitedSince(old.cancellationId(), Instant.now().minus(1, ChronoUnit.DAYS));
        kitchenDecides(old.orderId(), old.cancellationId(), "REJECTED", "KITCHEN_DECLINED").andExpect(status().isOk());

        getWith(ADMIN, REPORT + "?overdue=true").andExpect(jsonPath("$.totalItems").value(0));
        getWith(ADMIN, REPORT).andExpect(jsonPath("$.summary.pending.overdueCount").value(0))
                .andExpect(jsonPath("$.summary.pending.oldestRequestedAt").doesNotExist());
    }
}
