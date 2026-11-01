package com.restofy.order.api.controller;

import com.restofy.order.application.service.DataRetentionService;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 4: only the Payment service moves the payment status, and it is independent of fulfillment. */
class PaymentOrderEventApiTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final UUID RESTAURANT = DevFixtures.RESTAURANT_A;

    private ResultActions send(UUID orderId, UUID eventId, String status, Long amount, String currency, Instant at)
            throws Exception {
        return paymentEvent(service(PAYMENT_CREDENTIAL), paymentBody(eventId, orderId, status, amount, currency, at));
    }

    @Autowired
    private DataRetentionService retention;

    /** Rows that remember MESSAGES; the permanent capture markers are counted separately. */
    private int inboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_type NOT IN"
                + " ('PaymentCaptureApplied', 'PaymentCaptureReconciled')", Integer.class);
    }

    private int captureRows() {
        return count("payment_captures");
    }

    private int caseRows(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_reconciliation_cases WHERE order_id = ?",
                Integer.class, orderId);
    }

    private String captureStatus(UUID captureId) {
        return jdbc.queryForObject("SELECT status FROM payment_captures WHERE capture_id = ?", String.class, captureId);
    }

    // ---- who may call -----------------------------------------------------------------------------------------------

    @Test
    void onlyAVerifiedPaymentServiceMayCall() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Map<String, Object> body = paymentBody(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY", Instant.now());

        paymentEvent(null, body).andExpect(status().isUnauthorized());
        paymentEvent(service("not-a-known-credential"), body).andExpect(status().isUnauthorized());
        paymentEvent(service(DevFixtures.STAFF_ADMIN_A), body).andExpect(status().isUnauthorized());
        // other kinds of credential never act as the Payment service
        paymentEvent(guest(A1), body).andExpect(status().isForbidden());
        paymentEvent(staff(DevFixtures.STAFF_ADMIN_A), body).andExpect(status().isForbidden());
        paymentEvent(staff(DevFixtures.STAFF_WAITER_A), body).andExpect(status().isForbidden());
        paymentEvent(service(KITCHEN_CREDENTIAL), body).andExpect(status().isForbidden());
        // header hints open nothing
        mvc.perform(MockMvcRequestBuilders.post(PAYMENT_EVENTS)
                        .contentType("application/json").content(toJson(body)).header("X-Service", "PAYMENT")
                        .header("X-Payment-Status", "PAID"))
                .andExpect(status().isUnauthorized());

        assertEquals("NOT_REQUESTED", paymentOf(orderId));
        assertEquals(0, inboxRows());
        assertEquals(1, paymentHistory(orderId).size());
    }

    @Test
    void noClientOrStaffRequestCanSetAPaymentStatus() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        for (String credential : new String[]{guest(A1), staff(DevFixtures.STAFF_ADMIN_A),
                staff(DevFixtures.STAFF_WAITER_A), staff(DevFixtures.STAFF_KITCHEN_A)}) {
            mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/orders/" + orderId + "/payment-status").header("Authorization", credential)
                            .contentType("application/json").content("{\"status\":\"PAID\"}"))
                    .andExpect(status().isForbidden());
            // a payment word in the fulfillment endpoint is simply not a fulfillment status
            if (credential.startsWith("Staff")) {
                patchStatus(credential, orderId, "PAID").andExpect(status().isUnprocessableContent());
            }
        }
        // the checkout body cannot carry a payment status either
        placeOrder(A1, newKey(), Map.of("expectedCartVersion", 1, "expectedTotalMinor", 1, "paymentStatus", "PAID"))
                .andExpect(status().isBadRequest());
        assertEquals("NOT_REQUESTED", paymentOf(orderId));
    }

    // ---- the happy path and independence from fulfillment -----------------------------------------------------------

    @Test
    void aPaymentRunsPendingThenPaidAndIsRecordedAsThePaymentService() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID pendingEvent = UUID.randomUUID();
        send(orderId, pendingEvent, "PENDING", SALAD_DUE, "TRY", Instant.now().minusSeconds(60))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("RECEIVED"));
        pay(orderId, "PAID", 30).andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PAID"));

        assertEquals("PAID", paymentOf(orderId));
        assertEquals("RECEIVED", fulfillmentOf(orderId), "payment never moves fulfillment");
        assertEquals(List.of("-" + ">NOT_REQUESTED|SYSTEM|CHECKOUT", "NOT_REQUESTED>PENDING|SERVICE|PAYMENT_EVENT",
                "PENDING>PAID|SERVICE|PAYMENT_EVENT"), paymentHistory(orderId));
        assertEquals(1, fulfillmentHistory(orderId).size(), "the fulfillment timeline is untouched");
        assertEquals(pendingEvent.toString(), jdbc.queryForObject("SELECT source_event_id::text FROM"
                + " order_status_history WHERE order_id = ? AND to_status = 'PENDING' AND state_axis = 'PAYMENT'",
                String.class, orderId));
        assertNotNull(jdbc.queryForObject("SELECT payment_event_at FROM orders WHERE id = ?", Timestamp.class,
                orderId));
        assertEquals(2, inboxRows());
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId), "a verified PAID settles the remaining debt");
        assertEquals(SALAD_DUE, jdbc.queryForObject("SELECT initial_gross_minor FROM orders WHERE id = ?", Long.class,
                orderId), "but never the original total");
    }

    @Test
    void everyChangeWritesOneOutboxEventAndPaidAlsoSignalsQr() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        long before = versionOf(orderId);
        pay(orderId, "PENDING", 60).andExpect(status().isOk());
        assertEquals(before + 1, versionOf(orderId));
        assertEquals(List.of("NOTIFICATION", "WAITER"), destinationsOfPaymentChangeTo(orderId, "PENDING"),
                "Payment is not echoed its own change");
        pay(orderId, "PAID", 30).andExpect(status().isOk());
        assertEquals(List.of("NOTIFICATION", "QR", "WAITER"), destinationsOfPaymentChangeTo(orderId, "PAID"));
        assertEquals(2, paymentChangeEvents(orderId));

        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?"
                + " AND payload->>'toStatus' = 'PAID'", String.class, orderId);
        JsonNode event = json.readTree(payload);
        assertEquals("PENDING", event.path("fromStatus").asString());
        assertEquals("RECEIVED", event.path("fulfillmentStatus").asString());
        assertEquals(0L, event.path("dueMinor").asLong(), "nothing is owed after PAID");
        assertEquals(SALAD_DUE, event.path("amountMinor").asLong(), "the verified collected amount");
        assertFalse(payload.contains("guestSessionId"), "no guest session in an event");
    }

    @Test
    void paymentAndFulfillmentAreIndependentInBothDirections() throws Exception {
        // paid while still in preparation: still an active order
        UUID early = placeSaladOrder(A1);
        pay(early, "PENDING", 60).andExpect(status().isOk());
        pay(early, "PAID", 30).andExpect(status().isOk());
        patchStatus(staff(DevFixtures.STAFF_KITCHEN_A), early, "PREPARING").andExpect(status().isOk());
        assertEquals(1, body(getWith(guest(A1), "/api/v1/orders?view=active&paymentStatus=PAID")).path("totalItems")
                .asInt());
        assertEquals(0, body(getWith(guest(A1), "/api/v1/orders?view=completed")).path("totalItems").asInt(),
                "PAID is not COMPLETED");

        // completed but unpaid, then paid afterwards
        UUID late = placeSaladOrder(DevFixtures.TOKEN_A2);
        advanceTo(late, "COMPLETED");
        assertEquals("NOT_REQUESTED", paymentOf(late));
        pay(late, "PENDING", 60).andExpect(status().isOk());
        pay(late, "PAID", 30).andExpect(status().isOk());
        assertEquals("COMPLETED", fulfillmentOf(late));
        assertEquals("PAID", paymentOf(late));

        JsonNode detail = body(getWith(staff(DevFixtures.STAFF_ADMIN_A), "/api/v1/orders/" + early));
        assertEquals("PREPARING", detail.path("fulfillmentStatus").asString());
        assertEquals("PAID", detail.path("paymentStatus").asString());
    }

    // ---- amount and currency are checked ---------------------------------------------------------------------------

    @Test
    void aWrongAmountIsRefusedForPendingAndRecordedForCollectedMoney() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant at = Instant.now().minusSeconds(60);
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE - 1, "TRY", at).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE + 1, "TRY", at).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        send(orderId, UUID.randomUUID(), "PENDING", 0L, "TRY", at).andExpect(status().isConflict());
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "USD", at).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertEquals("NOT_REQUESTED", paymentOf(orderId));
        assertEquals(0, inboxRows());
        assertEquals(0, paymentChangeEvents(orderId));

        // the same amount in lower case currency is the same money
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "try", at).andExpect(status().isOk());
        // a PAID is money that really moved: when its amount does not match it is not applied, but it is NOT lost
        UUID capture = captureOf(orderId);
        send(orderId, UUID.randomUUID(), "PAID", SALAD_DUE - 100, "TRY", at.plusSeconds(10)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true));
        assertEquals("PENDING", paymentOf(orderId), "an unverified amount does not settle the order");
        assertEquals("NOT_APPLIED", captureStatus(capture));
        assertEquals("AMOUNT_MISMATCH", jdbc.queryForObject("SELECT not_applied_reason FROM payment_captures"
                + " WHERE capture_id = ?", String.class, capture));
        assertEquals(1, caseRows(orderId));
        assertEquals(1, reconciliationEvents(orderId));
    }

    @Test
    void pendingAndPaidMustCarryAnAmountButFailedAndCancelledNeedNot() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant at = Instant.now().minusSeconds(60);
        send(orderId, UUID.randomUUID(), "PENDING", null, null, at).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.parameter").value("amountMinor"));
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, null, at).andExpect(status().isUnprocessableContent());
        send(orderId, UUID.randomUUID(), "PAID", null, "TRY", at).andExpect(status().isUnprocessableContent());
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", at).andExpect(status().isOk());
        send(orderId, UUID.randomUUID(), "FAILED", null, null, at.plusSeconds(5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("FAILED", paymentOf(orderId));
    }

    // ---- redelivery, late and out-of-order events --------------------------------------------------------------------

    @Test
    void aRedeliveredEventReturnsTheOriginalOutcomeAndChangesNothing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID eventId = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(60);
        send(orderId, eventId, "PENDING", SALAD_DUE, "TRY", at).andExpect(jsonPath("$.duplicate").value(false));
        long version = versionOf(orderId);
        for (int i = 0; i < 3; i++) {
            send(orderId, eventId, "PENDING", SALAD_DUE, "TRY", at).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("APPLIED"))
                    .andExpect(jsonPath("$.duplicate").value(true));
        }
        assertEquals(version, versionOf(orderId));
        assertEquals(2, paymentHistory(orderId).size());
        assertEquals(1, paymentChangeEvents(orderId));
        assertEquals(1, inboxRows());

        // still recognised after the payment moved on
        pay(orderId, "PAID", 30).andExpect(status().isOk());
        send(orderId, eventId, "PENDING", SALAD_DUE, "TRY", at).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        assertEquals("PAID", paymentOf(orderId));
    }

    @Test
    void aRepeatedLateOrOlderEventIsAcknowledgedWithoutEffect() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        pay(orderId, "PAID", 50).andExpect(status().isOk());
        long version = versionOf(orderId);

        // same status again, a PENDING after the end, and a FAILED that happened BEFORE the payment completed
        for (String late : new String[]{"PAID", "PENDING"}) {
            pay(orderId, late, 40).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"))
                    .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        }
        send(orderId, UUID.randomUUID(), "FAILED", null, null, Instant.now().minusSeconds(70))
                .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        assertEquals(version, versionOf(orderId));
        assertEquals(3, paymentHistory(orderId).size(), "only the two real changes are in the history");
        assertEquals(2, paymentChangeEvents(orderId));
        assertEquals(5, inboxRows(), "ignored events are remembered, so their redelivery is recognised");
    }

    @Test
    void anOlderFailureCannotOverwriteANewerRetry() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 300).andExpect(status().isOk());
        pay(orderId, "FAILED", 200).andExpect(status().isOk());
        pay(orderId, "PENDING", 100).andExpect(status().isOk());       // a new attempt
        // the second FAILED of the FIRST attempt arrives late: legal in isolation, but older than the retry
        pay(orderId, "FAILED", 150).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        assertEquals("PENDING", paymentOf(orderId));
        pay(orderId, "PAID", 50).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals(List.of("-" + ">NOT_REQUESTED|SYSTEM|CHECKOUT", "NOT_REQUESTED>PENDING|SERVICE|PAYMENT_EVENT",
                "PENDING>FAILED|SERVICE|PAYMENT_EVENT", "FAILED>PENDING|SERVICE|PAYMENT_EVENT",
                "PENDING>PAID|SERVICE|PAYMENT_EVENT"), paymentHistory(orderId));
    }

    @Test
    void aPaidBeforeItsPendingWaitsDurablyAndIsAppliedWhenThePendingArrives() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID paidEvent = UUID.randomUUID();
        Instant paidAt = Instant.now().minusSeconds(30);
        UUID capture = captureOf(orderId);
        // the PAID is trusted (the Payment service sent it) but its attempt is unknown: it waits, it is not guessed at
        send(orderId, paidEvent, "PAID", SALAD_DUE, "TRY", paidAt).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.reconciliationRequired").value(false))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_REQUESTED"));
        assertEquals("PENDING_VERIFICATION", captureStatus(capture));
        assertEquals("NOT_REQUESTED", paymentOf(orderId), "a waiting PAID settles nothing");
        assertEquals(0, paymentChangeEvents(orderId));
        // the other statuses still cannot skip a step
        send(orderId, UUID.randomUUID(), "FAILED", null, null, paidAt).andExpect(status().isConflict());
        send(orderId, UUID.randomUUID(), "CANCELLED", null, null, paidAt).andExpect(status().isConflict());
        // a redelivery of the waiting message is answered as waiting and records nothing more
        send(orderId, paidEvent, "PAID", SALAD_DUE, "TRY", paidAt).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"));
        assertEquals(1, captureRows());

        // the PENDING of its attempt arrives and verifies it: amount and currency match, so it is applied
        pay(orderId, "PENDING", 60).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("PAID", paymentOf(orderId));
        assertEquals("APPLIED", captureStatus(capture));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(List.of("-" + ">NOT_REQUESTED|SYSTEM|CHECKOUT", "NOT_REQUESTED>PENDING|SERVICE|PAYMENT_EVENT",
                "PENDING>PAID|SERVICE|PAYMENT_EVENT"), paymentHistory(orderId));
        assertEquals(2, paymentChangeEvents(orderId));
        // and the original message now answers as applied
        send(orderId, paidEvent, "PAID", SALAD_DUE, "TRY", paidAt).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals(0, caseRows(orderId));
    }

    @Test
    void paidAndCancelledAreFinal() throws Exception {
        UUID paid = placeSaladOrder(A1);
        pay(paid, "PENDING", 100).andExpect(status().isOk());
        pay(paid, "PAID", 60).andExpect(status().isOk());
        // newer events that contradict a completed payment are conflicts, never silently applied or dropped
        pay(paid, "FAILED", 10).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        pay(paid, "CANCELLED", 10).andExpect(status().isConflict());
        assertEquals("PAID", paymentOf(paid));

        UUID cancelled = placeSaladOrder(DevFixtures.TOKEN_A2);
        pay(cancelled, "PENDING", 100).andExpect(status().isOk());
        pay(cancelled, "CANCELLED", 60).andExpect(status().isOk());
        // a PAID after the payment was cancelled is money that cannot be applied: recorded, not applied, not lost
        pay(cancelled, "PAID", 10).andExpect(status().isOk()).andExpect(jsonPath("$.reconciliationRequired").value(true));
        pay(cancelled, "PENDING", 10).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        assertEquals("CANCELLED", paymentOf(cancelled));
        assertEquals("RECEIVED", fulfillmentOf(cancelled), "a cancelled payment does not cancel the order");
    }

    // ---- cancelled orders ------------------------------------------------------------------------------------------------

    @Test
    void paymentEventsForACancelledOrderAreIgnoredButMoneyThatArrivedIsRecorded() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        pay(orderId, "PENDING", 60).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"))
                .andExpect(jsonPath("$.reconciliationRequired").value(false))
                .andExpect(jsonPath("$.fulfillmentStatus").value("CANCELLED"));
        pay(orderId, "FAILED", 50).andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"));
        // money for an order that no longer exists is never lost: acknowledged AND recorded for reconciliation
        pay(orderId, "PAID", 40).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"))
                .andExpect(jsonPath("$.reconciliationRequired").value(true));
        assertEquals("NOT_REQUESTED", paymentOf(orderId), "the order is not revived or marked paid");
        assertEquals("CANCELLED", fulfillmentOf(orderId));
        assertEquals(1, paymentHistory(orderId).size());
        assertEquals(1, reconciliationEvents(orderId));
    }

    private int reconciliationEvents(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderPaymentReconciliationRequired'", Integer.class, orderId);
    }

    // ---- tenant and identity ------------------------------------------------------------------------------------------------

    @Test
    void theOrderMustBelongToTheRestaurantTheEventNames() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Map<String, Object> foreign = paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY", Instant.now());
        foreign.put("restaurantId", DevFixtures.RESTAURANT_B);
        paymentEvent(service(PAYMENT_CREDENTIAL), foreign).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        send(UUID.randomUUID(), UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", Instant.now())
                .andExpect(status().isNotFound());
        assertEquals("NOT_REQUESTED", paymentOf(orderId));
        assertEquals(0, inboxRows());
    }

    @Test
    void anEventIdAlreadyUsedForAnotherOrderIsAConflict() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID eventId = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(10);
        send(first, eventId, "PENDING", SALAD_DUE, "TRY", at).andExpect(status().isOk());
        send(second, eventId, "PENDING", SALAD_DUE, "TRY", at).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals("NOT_REQUESTED", paymentOf(second));
    }

    @Test
    void kitchenEventIdsAndPaymentEventIdsAreSeparateNamespaces() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID sameId = UUID.randomUUID();
        kitchenEvent(service(KITCHEN_CREDENTIAL), kitchenBody(sameId, RESTAURANT, orderId, "PREPARING"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        send(orderId, sameId, "PENDING", SALAD_DUE, "TRY", Instant.now().minusSeconds(5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED")).andExpect(jsonPath("$.duplicate").value(false));
        assertEquals(2, inboxRows());
    }

    // ---- validation ------------------------------------------------------------------------------------------------------------

    @Test
    void theBodyIsValidatedStrictly() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant at = Instant.now().minusSeconds(10);
        for (String bad : new String[]{"NOT_REQUESTED", "REFUNDED", "DONE", "COMPLETED"}) {
            send(orderId, UUID.randomUUID(), bad, SALAD_DUE, "TRY", at).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.details.parameter").value("paymentStatus"));
        }
        send(orderId, UUID.randomUUID(), "PENDING", -5L, "TRY", at).andExpect(status().isUnprocessableContent());
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRYY", at).andExpect(status().isUnprocessableContent());
        for (String missing : new String[]{"eventId", "restaurantId", "orderId", "paymentStatus", "occurredAt"}) {
            Map<String, Object> body = paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY", at);
            body.remove(missing);
            paymentEvent(service(PAYMENT_CREDENTIAL), body).andExpect(status().isUnprocessableContent());
        }
        // a timestamp from the far future would freeze every later event, so it is refused
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", Instant.now().plusSeconds(3600))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.parameter").value("occurredAt"));
        Map<String, Object> extra = paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY", at);
        extra.put("fulfillmentStatus", "COMPLETED");
        paymentEvent(service(PAYMENT_CREDENTIAL), extra).andExpect(status().isBadRequest());
        paymentEvent(service(PAYMENT_CREDENTIAL), "not json").andExpect(status().isBadRequest());
        // the status text is case-insensitive
        send(orderId, UUID.randomUUID(), "pending", SALAD_DUE, "TRY", at).andExpect(status().isOk());
        assertEquals("PENDING", paymentOf(orderId));
        assertEquals(1, inboxRows());
    }

    // ---- the database repeats the rules ---------------------------------------------------------------------------------

    @Test
    void theDatabaseItselfRefusesIllegalPaymentMoves() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        assertGuardRefuses(orderId, "PAID");
        assertGuardRefuses(orderId, "FAILED");
        jdbc.update("UPDATE orders SET payment_status = 'PENDING' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "NOT_REQUESTED");
        jdbc.update("UPDATE orders SET payment_status = 'FAILED' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "PAID");
        assertGuardRefuses(orderId, "NOT_REQUESTED");
        jdbc.update("UPDATE orders SET payment_status = 'PENDING' WHERE id = ?", orderId);
        jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE id = ?", orderId);
        for (String target : new String[]{"PENDING", "FAILED", "CANCELLED", "NOT_REQUESTED"}) {
            assertGuardRefuses(orderId, target);
        }
        assertEquals(1, jdbc.update("UPDATE orders SET payment_status = 'PAID' WHERE id = ?", orderId),
                "writing the same value is not a move");
    }

    // ---- K-07: a PAID that cannot be applied is recorded for reconciliation -------------------------------------------

    @Test
    void aPaidForACancelledOrderLeavesADurableAuditTrailAndIsIdempotent() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        UUID eventId = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(30);
        // the reported amount is only recorded here: nothing is applied, so it is not checked against the order
        send(orderId, eventId, "PAID", 12_345L, "TRY", at).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true)).andExpect(jsonPath("$.duplicate").value(false));

        assertEquals(1, inboxRows());
        assertEquals("PaymentPaidNotApplicable", jdbc.queryForObject("SELECT event_type FROM inbox_events", String.class));
        assertEquals(1, captureRows(), "the collection is recorded for good");
        assertEquals("NOT_APPLIED", jdbc.queryForObject("SELECT status FROM payment_captures", String.class));
        assertEquals("ORDER_CANCELLED", jdbc.queryForObject("SELECT not_applied_reason FROM payment_captures",
                String.class));
        assertEquals(12_345L, jdbc.queryForObject("SELECT amount_minor FROM payment_captures", Long.class));
        assertEquals(1, caseRows(orderId));
        assertEquals(1, reconciliationEvents(orderId));
        JsonNode event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderPaymentReconciliationRequired'", String.class, orderId));
        assertEquals("ORDER_CANCELLED", event.path("reason").asString());
        assertEquals(eventId.toString(), event.path("reportedPaymentEventId").asString());
        assertEquals(12_345L, event.path("reportedAmountMinor").asLong());
        assertEquals("TRY", event.path("reportedCurrency").asString());
        assertEquals("CANCELLED", event.path("fulfillmentStatus").asString());
        assertEquals("NOT_REQUESTED", event.path("paymentStatus").asString());
        assertEquals(List.of("ADMIN", "NOTIFICATION", "PAYMENT"), jdbc.queryForList("""
                SELECT d.destination FROM outbox_events e JOIN outbox_deliveries d ON d.event_id = e.id
                WHERE e.aggregate_id = ? AND e.event_type = 'OrderPaymentReconciliationRequired' ORDER BY d.destination
                """, String.class, orderId));
        assertFalse(event.toString().contains("guestSessionId"));

        // a redelivery returns the original answer and records nothing more
        send(orderId, eventId, "PAID", 12_345L, "TRY", at).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.reconciliationRequired").value(true));
        assertEquals(1, reconciliationEvents(orderId));
        assertEquals(1, inboxRows());
        assertEquals(0, paymentChangeEvents(orderId), "no status change was announced");
    }

    @Test
    void aPaidAfterThePaymentWasCancelledIsRecordedToo() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        pay(orderId, "CANCELLED", 60).andExpect(status().isOk());
        send(orderId, UUID.randomUUID(), "PAID", SALAD_DUE, "TRY", Instant.now().minusSeconds(10))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reconciliationRequired").value(true));
        assertEquals("CANCELLED", paymentOf(orderId));
        assertEquals("PAYMENT_CANCELLED", json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events"
                + " WHERE aggregate_id = ? AND event_type = 'OrderPaymentReconciliationRequired'", String.class, orderId))
                .path("reason").asString());
    }

    @Test
    void aPaidOfANewAttemptThatArrivedBeforeItsPendingWaitsAndIsThenApplied() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 200).andExpect(status().isOk());
        pay(orderId, "FAILED", 100).andExpect(status().isOk());
        UUID secondAttempt = UUID.randomUUID();
        UUID capture = UUID.randomUUID();
        // the new attempt's PENDING is still on its way: the PAID waits (it is not a discrepancy and it is not lost)
        payWith(orderId, "PAID", 50, secondAttempt, capture).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"));
        assertEquals(0, reconciliationEvents(orderId));
        assertEquals("FAILED", paymentOf(orderId));
        payWith(orderId, "PENDING", 60, secondAttempt, null).andExpect(status().isOk());
        assertEquals("PAID", paymentOf(orderId));
        assertEquals("APPLIED", captureStatus(capture));
    }

    @Test
    void aWaitingPaidWhoseAmountDoesNotMatchBecomesACaseWhenItsPendingArrives() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID attempt = UUID.randomUUID();
        UUID capture = UUID.randomUUID();
        paymentEvent(service(PAYMENT_CREDENTIAL), paymentBodyWith(UUID.randomUUID(), orderId, "PAID", SALAD_DUE - 500,
                "TRY", Instant.now().minusSeconds(30), attempt, capture)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"));
        payWith(orderId, "PENDING", 20, attempt, null).andExpect(status().isOk());
        assertEquals("PENDING", paymentOf(orderId), "money of another amount does not settle the order");
        assertEquals("NOT_APPLIED", captureStatus(capture));
        assertEquals(1, caseRows(orderId));
        assertEquals(1, reconciliationEvents(orderId));
    }

    @Test
    void aWaitingPaidStaysVisibleWhenTheOrderIsCancelledBeforeItsPending() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID capture = captureOf(orderId);
        pay(orderId, "PAID", 30).andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"));
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        // the PENDING of a cancelled order is ignored, so the waiting PAID stays visible until a person resolves it
        pay(orderId, "PENDING", 20).andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"));
        assertEquals("PENDING_VERIFICATION", captureStatus(capture));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_captures WHERE status = 'APPLIED'",
                Integer.class));
    }

    // ---- K-08: debt, failed payments of cancelled orders ---------------------------------------------------------------

    @Test
    void paidSettlesTheDebtAndKeepsTheAuditOfWhatWasReported() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        assertEquals(SALAD_DUE, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId), "a pending payment owes the same");
        pay(orderId, "FAILED", 80).andExpect(status().isOk());
        pay(orderId, "PENDING", 60).andExpect(status().isOk());
        assertEquals(SALAD_DUE, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class,
                orderId), "a failed attempt and a retry do not change the debt");
        pay(orderId, "PAID", 40).andExpect(status().isOk());

        JsonNode detail = body(getWith(staff(DevFixtures.STAFF_ADMIN_A), "/api/v1/orders/" + orderId));
        assertEquals(0L, detail.path("currentDueMinor").asLong());
        assertEquals(SALAD_DUE, detail.path("amounts").path("grossMinor").asLong(), "the original total is untouched");
        String reason = jdbc.queryForObject("SELECT reason FROM order_status_history"
                + " WHERE order_id = ? AND to_status = 'PAID'", String.class, orderId);
        assertTrue(reason.contains("amountMinor=9000;currency=TRY"), reason);
        assertTrue(reason.contains("attemptId=" + attemptOf(orderId)) && reason.contains("captureId=" + captureOf(orderId)),
                reason);
        // the repeat of a finished payment cannot change the settled debt or the history
        pay(orderId, "PAID", 30).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
    }

    @Test
    void aFailedPaymentIsClosedOnlyOnceTheOrderItselfIsCancelled() throws Exception {
        UUID live = placeSaladOrder(A1);
        pay(live, "PENDING", 100).andExpect(status().isOk());
        pay(live, "FAILED", 80).andExpect(status().isOk());
        // the order is alive: the guest may try again, so the failed attempt is not closed
        pay(live, "CANCELLED", 60).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertEquals("FAILED", paymentOf(live));

        UUID dead = placeSaladOrder(DevFixtures.TOKEN_A2);
        pay(dead, "PENDING", 100).andExpect(status().isOk());
        pay(dead, "FAILED", 80).andExpect(status().isOk());
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", dead);
        pay(dead, "CANCELLED", 60).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
        assertEquals(List.of("-" + ">NOT_REQUESTED|SYSTEM|CHECKOUT", "NOT_REQUESTED>PENDING|SERVICE|PAYMENT_EVENT",
                "PENDING>FAILED|SERVICE|PAYMENT_EVENT", "FAILED>CANCELLED|SERVICE|PAYMENT_EVENT"), paymentHistory(dead));
    }

    @Test
    void anOpenPaymentAttemptOfACancelledOrderCanBeClosedButNotRestarted() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        pay(orderId, "CANCELLED", 60).andExpect(jsonPath("$.outcome").value("APPLIED"));
        pay(orderId, "PENDING", 30).andExpect(jsonPath("$.outcome").value("IGNORED_ORDER_CLOSED"));
        assertEquals("CANCELLED", paymentOf(orderId));
    }

    @Test
    void theDatabaseClosesAFailedPaymentOnlyForACancelledOrder() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        jdbc.update("UPDATE orders SET payment_status = 'PENDING' WHERE id = ?", orderId);
        jdbc.update("UPDATE orders SET payment_status = 'FAILED' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "CANCELLED");
        assertEquals(1, jdbc.update("UPDATE orders SET payment_status = 'CANCELLED', fulfillment_status = 'CANCELLED'"
                + " WHERE id = ?", orderId));
    }

    // ---- K-12: event time ---------------------------------------------------------------------------------------------------

    @Test
    void theClockToleranceIsFiveMinutesAheadAndOldEventsAreNeverRefusedForBeingOld() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        send(orderId, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", Instant.now().plusSeconds(240))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        send(orderId, UUID.randomUUID(), "FAILED", null, null, Instant.now().plusSeconds(400))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.details.parameter").value("occurredAt"));
        UUID old = placeSaladOrder(DevFixtures.TOKEN_A2);
        send(old, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", Instant.now().minusSeconds(30L * 24 * 3600))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
    }

    @Test
    void aLatePaidIsAppliedEvenThoughANewerEventWasAppliedFirstAndEqualTimesAreFine() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 300).andExpect(status().isOk());
        pay(orderId, "FAILED", 200).andExpect(status().isOk());
        pay(orderId, "PENDING", 100).andExpect(status().isOk());          // a second attempt, applied last
        // the PAID of the first attempt happened BEFORE the retry but is delivered after it: still money, still applied
        pay(orderId, "PAID", 250).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("PAID", paymentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        // the stored event time never moves backwards
        assertTrue(jdbc.queryForObject("SELECT payment_event_at FROM orders WHERE id = ?", Timestamp.class,
                orderId).toInstant().isAfter(Instant.now().minusSeconds(150)));

        // two events with exactly the same timestamp are ordered by the state machine, not refused
        UUID same = placeSaladOrder(DevFixtures.TOKEN_A2);
        Instant at = Instant.now().minusSeconds(60);
        send(same, UUID.randomUUID(), "PENDING", SALAD_DUE, "TRY", at).andExpect(jsonPath("$.outcome").value("APPLIED"));
        send(same, UUID.randomUUID(), "PAID", SALAD_DUE, "TRY", at).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("PAID", paymentOf(same));
    }

    // ---- payment references: attempt (a try) and capture (the money) -------------------------------------------------

    @Test
    void theSameCollectionUnderAnotherMessageIdIsNeverASecondCharge() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        pay(orderId, "PAID", 60).andExpect(status().isOk());
        int historyRows = paymentHistory(orderId).size();
        int events = paymentChangeEvents(orderId);

        for (int i = 0; i < 3; i++) {
            pay(orderId, "PAID", 50).andExpect(status().isOk())            // same capture, fresh message id every time
                    .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"))
                    .andExpect(jsonPath("$.reconciliationRequired").value(false));
        }
        assertEquals(historyRows, paymentHistory(orderId).size());
        assertEquals(events, paymentChangeEvents(orderId));
        assertEquals(0, reconciliationEvents(orderId), "the same money is not a discrepancy");
        assertEquals(1, captureRows());
    }

    @Test
    void aDifferentCollectionOnAnAlreadyPaidOrderIsAPossibleDoubleChargeAndIsReconciled() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        pay(orderId, "PAID", 60).andExpect(status().isOk());

        UUID secondCapture = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var body = paymentBodyWith(messageId, orderId, "PAID", SALAD_DUE, "TRY", Instant.now().minusSeconds(30),
                attemptOf(orderId), secondCapture);
        paymentEvent(service(PAYMENT_CREDENTIAL), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        assertEquals(1, reconciliationEvents(orderId));
        JsonNode event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?"
                + " AND event_type = 'OrderPaymentReconciliationRequired'", String.class, orderId));
        assertEquals("SECOND_CAPTURE", event.path("reason").asString());
        assertEquals(secondCapture.toString(), event.path("reportedCaptureId").asString());
        assertEquals(attemptOf(orderId).toString(), event.path("reportedPaymentAttemptId").asString());

        // the same second collection again (new message id) or the same message again: recorded once
        payWith(orderId, "PAID", 20, attemptOf(orderId), secondCapture).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true));
        paymentEvent(service(PAYMENT_CREDENTIAL), body).andExpect(jsonPath("$.duplicate").value(true));
        assertEquals(1, reconciliationEvents(orderId));
        // and the order is still settled exactly once
        assertEquals("PAID", paymentOf(orderId));
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
        assertEquals(3, paymentHistory(orderId).size(), "no history row for a payment that was not applied");
    }

    @Test
    void afterTheOrdinaryInboxCleanupARedeliveredPaymentStillHasNoSecondEffect() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID pendingMessage = UUID.randomUUID();
        UUID paidMessage = UUID.randomUUID();
        Instant pendingAt = Instant.now().minusSeconds(100);
        Instant paidAt = Instant.now().minusSeconds(60);
        send(orderId, pendingMessage, "PENDING", SALAD_DUE, "TRY", pendingAt).andExpect(status().isOk());
        send(orderId, paidMessage, "PAID", SALAD_DUE, "TRY", paidAt).andExpect(status().isOk());
        // a second collection is recorded for reconciliation too
        UUID secondCapture = UUID.randomUUID();
        UUID reconciledMessage = UUID.randomUUID();
        paymentEvent(service(PAYMENT_CREDENTIAL), paymentBodyWith(reconciledMessage, orderId, "PAID", SALAD_DUE, "TRY",
                paidAt, attemptOf(orderId), secondCapture)).andExpect(jsonPath("$.reconciliationRequired").value(true));

        // 200 days pass and the ordinary cleanup runs
        jdbc.update("UPDATE inbox_events SET received_at = now() - interval '200 days'");
        assertEquals(3, retention.purgeProcessedInboxEvents(), "every message row goes");
        assertEquals(0, inboxRows());
        assertEquals(2, captureRows(), "but both collections stay known for ever");
        assertEquals(1, caseRows(orderId));

        int historyRows = paymentHistory(orderId).size();
        int events = paymentChangeEvents(orderId);
        // every old message arrives again
        send(orderId, pendingMessage, "PENDING", SALAD_DUE, "TRY", pendingAt).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        send(orderId, paidMessage, "PAID", SALAD_DUE, "TRY", paidAt).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED_STALE")).andExpect(jsonPath("$.reconciliationRequired")
                        .value(false));
        paymentEvent(service(PAYMENT_CREDENTIAL), paymentBodyWith(reconciledMessage, orderId, "PAID", SALAD_DUE, "TRY",
                paidAt, attemptOf(orderId), secondCapture)).andExpect(jsonPath("$.reconciliationRequired").value(true));

        assertEquals("PAID", paymentOf(orderId));
        assertEquals(historyRows, paymentHistory(orderId).size(), "no second financial effect");
        assertEquals(events, paymentChangeEvents(orderId));
        assertEquals(1, reconciliationEvents(orderId), "and no second reconciliation alert for the same collection");
        assertEquals(0L, jdbc.queryForObject("SELECT current_due_minor FROM orders WHERE id = ?", Long.class, orderId));
    }

    @Test
    void oneCollectionCannotBeUsedForTwoOrders() throws Exception {
        UUID first = placeSaladOrder(A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID sharedCapture = UUID.randomUUID();
        pay(first, "PENDING", 100).andExpect(status().isOk());
        pay(second, "PENDING", 100).andExpect(status().isOk());
        payWith(first, "PAID", 60, attemptOf(first), sharedCapture).andExpect(jsonPath("$.outcome").value("APPLIED"));

        payWith(second, "PAID", 50, attemptOf(second), sharedCapture).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        assertEquals("PENDING", paymentOf(second), "the second order is not marked paid by someone else's money");
        assertEquals("CAPTURE_USED_ON_OTHER_ORDER", json.readTree(jdbc.queryForObject("SELECT payload::text FROM"
                + " outbox_events WHERE aggregate_id = ? AND event_type = 'OrderPaymentReconciliationRequired'",
                String.class, second)).path("reason").asString());
        assertEquals("PAID", paymentOf(first));
    }

    @Test
    void aPaidMustNameAnAttemptTheOrderHasSeenOtherwiseItWaitsForThatAttempt() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        UUID unknownAttempt = UUID.randomUUID();
        UUID capture = UUID.randomUUID();
        payWith(orderId, "PAID", 60, unknownAttempt, capture).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PENDING_VERIFICATION"));
        assertEquals("PENDING", paymentOf(orderId), "a PAID of an attempt this order never saw settles nothing");
        assertEquals("PENDING_VERIFICATION", captureStatus(capture));

        // a known attempt applies at once, and the second collection is then a possible double charge
        payWith(orderId, "PAID", 50, attemptOf(orderId), UUID.randomUUID()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("PAID", paymentOf(orderId));
    }

    @Test
    void anOldAttemptsFailureCannotEndTheNewerAttemptAndAnUnknownOneIsRefused() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        payWith(orderId, "PENDING", 300, first, null).andExpect(status().isOk());
        payWith(orderId, "FAILED", 250, first, null).andExpect(status().isOk());
        payWith(orderId, "PENDING", 200, second, null).andExpect(status().isOk());

        // the first attempt's failure is delivered again later: it belongs to an attempt that is over
        payWith(orderId, "FAILED", 100, first, null).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        payWith(orderId, "FAILED", 100, UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MISMATCH"));
        assertEquals("PENDING", paymentOf(orderId));
        // and a PENDING for an attempt that already started is just a repeat
        payWith(orderId, "PENDING", 90, second, null).andExpect(jsonPath("$.outcome").value("IGNORED_STALE"));
        // a brand new attempt cannot start while the current one is still open
        payWith(orderId, "PENDING", 80, UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        payWith(orderId, "FAILED", 70, second, null).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertEquals("FAILED", paymentOf(orderId));
    }

    @Test
    void aLatePaidOfAnEarlierAttemptIsStillMoneyAndIsApplied() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID first = UUID.randomUUID();
        payWith(orderId, "PENDING", 300, first, null).andExpect(status().isOk());
        payWith(orderId, "FAILED", 250, first, null).andExpect(status().isOk());
        payWith(orderId, "PENDING", 200, UUID.randomUUID(), null).andExpect(status().isOk());
        UUID capture = UUID.randomUUID();
        payWith(orderId, "PAID", 280, first, capture).andExpect(jsonPath("$.outcome").value("APPLIED"));
        String reason = jdbc.queryForObject("SELECT reason FROM order_status_history WHERE order_id = ? AND"
                + " to_status = 'PAID'", String.class, orderId);
        assertTrue(reason.contains("attemptId=" + first) && reason.contains("captureId=" + capture), reason);
    }

    @Test
    void theReferencesAreValidatedStrictly() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        Instant at = Instant.now().minusSeconds(10);
        // an attempt id is required on every event
        Map<String, Object> noAttempt = paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY", at);
        noAttempt.remove("paymentAttemptId");
        paymentEvent(service(PAYMENT_CREDENTIAL), noAttempt).andExpect(status().isUnprocessableContent());
        // a collection id is required on PAID and forbidden elsewhere: an attempt is not a collection
        pay(orderId, "PENDING", 100).andExpect(status().isOk());
        Map<String, Object> paidWithoutCapture = paymentBody(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY", at);
        paidWithoutCapture.remove("captureId");
        paymentEvent(service(PAYMENT_CREDENTIAL), paidWithoutCapture).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.parameter").value("captureId"));
        payWith(orderId, "FAILED", 50, attemptOf(orderId), UUID.randomUUID()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.parameter").value("captureId"));
        // malformed ids
        Map<String, Object> bad = paymentBody(UUID.randomUUID(), orderId, "PAID", SALAD_DUE, "TRY", at);
        bad.put("captureId", "not-a-uuid");
        paymentEvent(service(PAYMENT_CREDENTIAL), bad).andExpect(status().isBadRequest());
        assertEquals("PENDING", paymentOf(orderId));
    }

    private void assertGuardRefuses(UUID orderId, String target) {
        DataAccessException error = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE orders SET payment_status = ? WHERE id = ?", target, orderId), target);
        assertTrue(error.getMessage().contains("invalid payment transition"), error.getMessage());
    }
}
