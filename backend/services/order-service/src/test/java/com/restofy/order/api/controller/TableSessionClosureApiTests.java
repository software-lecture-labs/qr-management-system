package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001 (Order Service side), settled closure: the QR Service asks whether a table session may be closed, the Order
 * Service looks at ALL orders of the session and, when eligible, refuses new orders for it until the QR Service
 * confirms. The permit shape, idle expiry and the order state have their own test classes.
 */
class TableSessionClosureApiTests extends AbstractClosureApiTest {

    // ---- who may ask ------------------------------------------------------------------------------------------------

    @Test
    void onlyTheQrServiceMayUseTheLifecycleEndpoints() throws Exception {
        UUID operation = UUID.randomUUID();
        Map<String, Object> body = settledBody(RESTAURANT, operation, DIGEST);

        prepareRaw(null, SESSION, body).andExpect(status().isUnauthorized());
        prepareRaw(service("not-a-known-credential"), SESSION, body).andExpect(status().isUnauthorized());
        prepareRaw(guest(DevFixtures.TOKEN_A1), SESSION, body).andExpect(status().isForbidden());
        prepareRaw(staff(DevFixtures.STAFF_ADMIN_A), SESSION, body).andExpect(status().isForbidden());
        prepareRaw(service(KITCHEN_CREDENTIAL), SESSION, body).andExpect(status().isForbidden());
        prepareRaw(service(PAYMENT_CREDENTIAL), SESSION, body).andExpect(status().isForbidden());

        lookup(guest(DevFixtures.TOKEN_A1), SESSION, operation, RESTAURANT).andExpect(status().isForbidden());
        lookup(service(KITCHEN_CREDENTIAL), SESSION, operation, RESTAURANT).andExpect(status().isForbidden());
        confirm(staff(DevFixtures.STAFF_ADMIN_A), SESSION, operation, RESTAURANT, "CLOSED")
                .andExpect(status().isForbidden());
        confirm(service(PAYMENT_CREDENTIAL), SESSION, operation, RESTAURANT, "CLOSED")
                .andExpect(status().isForbidden());
        orderState(guest(DevFixtures.TOKEN_A1), SESSION, RESTAURANT).andExpect(status().isForbidden());

        assertEquals(0, closures());
    }

    @Test
    void theQrServiceCredentialOpensNothingElse() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);

        getWith(QR, "/api/v1/orders/" + orderId).andExpect(status().isForbidden());
        getWith(QR, "/api/v1/carts/current").andExpect(status().isForbidden());
        patchStatus(QR, orderId, "PREPARING").andExpect(status().isForbidden());
        kitchenEvent(QR, kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING"))
                .andExpect(status().isForbidden());
        paymentEvent(QR, paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY", Instant.now()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCommandThatIsMissingRequiredFieldsIsRefused() throws Exception {
        UUID operation = UUID.randomUUID();
        for (String missing : List.of("restaurantId", "operationId", "purpose", "commandDigest")) {
            Map<String, Object> body = settledBody(RESTAURANT, operation, DIGEST);
            body.remove(missing);
            prepareRaw(QR, SESSION, body).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, "not-a-digest"))
                .andExpect(status().isUnprocessableEntity());
        confirmRaw(QR, SESSION, UUID.randomUUID(), RESTAURANT, "MAYBE", null).andExpect(status().is4xxClientError());

        assertEquals(0, closures());
    }

    // ---- eligibility (K2, K3, K7) -----------------------------------------------------------------------------------

    @Test
    void aTableSessionWithoutOrdersIsNotEligibleAndNothingIsHeld() throws Exception {
        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.sourceStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.blockers[0]").value("NO_ORDERS"))
                .andExpect(jsonPath("$.orderCount").value(0));

        assertEquals(0, closures());
    }

    @Test
    void finishedAndPaidOrdersPrepareTheClosure() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();

        JsonNode prepared = body(prepare(operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"))
                .andExpect(jsonPath("$.outcome").value("ELIGIBLE"))
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT.toString()))
                .andExpect(jsonPath("$.tableSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.blockers").isEmpty()));

        assertEquals(64, prepared.path("scopeFingerprint").asString().length());
        assertEquals(1, closures());
        assertEquals("PREPARED", jdbc.queryForObject("SELECT status FROM table_session_closures", String.class));
    }

    @Test
    void paidButNotYetServedKeepsTheTableOpen() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"));

        assertEquals(List.of("ORDER_NOT_COMPLETED"), blockersOf(result));
        assertEquals(0, closures());
    }

    @Test
    void servedButNotPaidKeepsTheTableOpen() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"));

        assertEquals(List.of("PAYMENT_NOT_SETTLED"), blockersOf(result));
        assertEquals(0, closures());
    }

    @Test
    void aPaymentStillInFlightIsUnknownAndHoldsNothing() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.sourceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN"));

        assertEquals(List.of("PAYMENT_IN_PROGRESS"), blockersOf(result));
        assertEquals(0, closures());
        // nothing is held, so the table still takes orders
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    @Test
    void theOrdersOfEveryGuestOfTheSessionCount() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.orderCount").value(2));
        assertEquals(List.of("ORDER_NOT_COMPLETED", "PAYMENT_NOT_SETTLED"), blockersOf(result));

        advanceTo(second, "COMPLETED");
        pay(second, "PENDING", 30).andExpect(status().isOk());
        pay(second, "PAID", 20).andExpect(status().isOk());
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void aGuestWhoOrderedNothingOwesNothing() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        // guest 2 only looks at the menu and fills a cart
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.outcome").value("ELIGIBLE"))
                .andExpect(jsonPath("$.orderCount").value(1));
    }

    @Test
    void aCancelledOrderOwesNothingButAnotherUnpaidOneStillBlocks() throws Exception {
        UUID cancelled = placeSaladOrder(DevFixtures.TOKEN_A1);
        cancelAsGuest(cancelled, DevFixtures.TOKEN_A1);
        finishedAndPaidOrder(DevFixtures.TOKEN_A2);

        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void theOrdersOfAnotherRestaurantAreNeverLookedAt() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);

        // asking about the same session id under another restaurant sees no orders and holds nothing of restaurant A
        prepareRaw(QR, SESSION, settledBody(DevFixtures.RESTAURANT_B, UUID.randomUUID(), DIGEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.blockers[0]").value("NO_ORDERS"));
        assertEquals(0, closures());
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    // ---- K8: checkout and the closure are ordered ---------------------------------------------------------------------

    @Test
    void oncePreparedANewOrderForTheSessionIsRefusedAndNothingIsWritten() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"));

        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        int orders = count("orders");
        int idempotency = count("idempotency_records");
        int events = count("outbox_events");
        int history = count("order_status_history");
        long counter = jdbc.queryForObject("SELECT last_value FROM order_number_counters", Long.class);

        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));

        assertEquals(orders, count("orders"));
        assertEquals(idempotency, count("idempotency_records"), "a refused order claims no idempotency key");
        assertEquals(events, count("outbox_events"));
        assertEquals(history, count("order_status_history"));
        assertEquals(counter, jdbc.queryForObject("SELECT last_value FROM order_number_counters", Long.class),
                "a refused order does not use an order number");
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts WHERE guest_session_id = ?", String.class,
                DevFixtures.GUEST_A2), "the guest's cart is untouched");
    }

    @Test
    void anOrderThatWasPlacedBeforeThePreparationIsPartOfItsScope() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        // a second order is accepted before anybody asked to close
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());

        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void otherTableSessionsAreNotAffectedByAPreparedClosure() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"));

        orderAgain(DevFixtures.TOKEN_B1).andExpect(status().isCreated());
    }

    // ---- K9: one durable operation ------------------------------------------------------------------------------------

    @Test
    void askingAgainWithTheSameOperationReturnsTheSameClosureAndPreparesNothingNew() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();

        JsonNode first = body(prepare(operation).andExpect(status().isOk()));
        JsonNode second = body(prepare(operation).andExpect(status().isOk()));

        assertEquals(first.path("state"), second.path("state"));
        assertEquals(first.path("scopeFingerprint"), second.path("scopeFingerprint"));
        assertEquals(first.path("sourceStatus"), second.path("sourceStatus"));
        assertEquals(1, closures());
    }

    @Test
    void aSecondOperationForAPreparedSessionIsRefused() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID first = UUID.randomUUID();
        prepare(first).andExpect(jsonPath("$.state").value("PREPARED"));

        prepare(UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_IN_PROGRESS"))
                .andExpect(jsonPath("$.details.operationId").value(first.toString()));
        assertEquals(1, closures());
    }

    @Test
    void anOperationIdCannotBeReusedForAnotherCommand() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));

        prepareRaw(QR, DevFixtures.TABLE_SESSION_B1, settledBody(RESTAURANT, operation, DIGEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        prepareRaw(QR, SESSION, settledBody(DevFixtures.RESTAURANT_B, operation, DIGEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // the same id with another command digest is another command
        prepareRaw(QR, SESSION, settledBody(RESTAURANT, operation, "e".repeat(64)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // and so is the same id for another purpose
        prepareRaw(QR, SESSION, idleBody(operation, Instant.now(), Instant.now().minusSeconds(60), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aQrServiceThatLostTheResponseCanAskWhatBecameOfTheOperation() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        JsonNode prepared = body(prepare(operation));

        JsonNode looked = body(lookup(operation).andExpect(status().isOk()));
        assertEquals(prepared.path("state"), looked.path("state"));
        assertEquals(prepared.path("scopeFingerprint"), looked.path("scopeFingerprint"));
        assertEquals("AUTHORISED", looked.path("sourceStatus").asString());

        // an operation the Order Service never saw, or one of another session or restaurant, is simply NOT_STARTED
        lookup(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED"));
        lookup(QR, DevFixtures.TABLE_SESSION_B1, operation, RESTAURANT).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED"));
        lookup(QR, SESSION, operation, DevFixtures.RESTAURANT_B).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED"));
    }

    @Test
    void aPreparedClosureNeverExpiresByItself() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        // a long time passes: nothing releases it, a timeout does not prove the closing failed
        jdbc.execute("ALTER TABLE table_session_closures DISABLE TRIGGER trg_table_session_closures_guard");
        try {
            jdbc.update("UPDATE table_session_closures SET prepared_at = prepared_at - interval '30 days'");
        } finally {
            jdbc.execute("ALTER TABLE table_session_closures ENABLE TRIGGER trg_table_session_closures_guard");
        }

        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
    }

    // ---- the QR Service reports the result ------------------------------------------------------------------------------

    @Test
    void closedCompletesTheClosureAndOrdersStayRefused() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));

        confirm(operation, "NOT_CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        // a closed session cannot be prepared again either
        prepare(UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_IN_PROGRESS"));
    }

    @Test
    void notClosedReleasesTheHoldAndOrdersAreAcceptedAgain() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isConflict());

        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").isNotEmpty()).andExpect(jsonPath("$.sourceStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.checkoutFenced").value(false));
        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        confirm(operation, "CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isCreated());
        // and a later, new operation is possible once the new order is finished and paid
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("NOT_PREPARED"));
    }

    @Test
    void closedWithoutAnyChangeRecordsNoDrift() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.scopeDrift").value(false)).andExpect(jsonPath("$.driftBlockers").isEmpty());

        assertEquals(0, count("table_session_closure_drifts"));
    }

    @Test
    void closedIsAlwaysAcceptedAndWhatChangedSincePreparationIsRecorded() throws Exception {
        UUID orderId = finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        // something about the money changed in between (here: the order owes money again)
        jdbc.update("UPDATE orders SET current_due_minor = 100 WHERE id = ?", orderId);

        JsonNode first = body(confirm(operation, "CLOSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.scopeDrift").value(true))
                .andExpect(jsonPath("$.driftBlockers[0]").value("PAYMENT_NOT_SETTLED")));

        var row = jdbc.queryForMap("SELECT outcome_at_confirm, blockers, restaurant_id, table_session_id"
                + " FROM table_session_closure_drifts WHERE operation_id = ?", operation);
        assertEquals("NOT_ELIGIBLE", row.get("outcome_at_confirm"));
        assertEquals("PAYMENT_NOT_SETTLED", row.get("blockers"));
        assertEquals(RESTAURANT, row.get("restaurant_id"));
        assertEquals(SESSION, row.get("table_session_id"));

        // asking again never writes a second record and returns the same drift, also through the lookup
        JsonNode second = body(confirm(operation, "CLOSED").andExpect(status().isOk()));
        assertEquals(first.path("scopeDrift"), second.path("scopeDrift"));
        assertEquals(first.path("driftBlockers"), second.path("driftBlockers"));
        lookup(operation).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.scopeDrift").value(true));
        assertEquals(1, count("table_session_closure_drifts"));

        // the operation is final and the table stays closed for orders
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        confirm(operation, "NOT_CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void aMoneyReportThatArrivesAfterThePreparationIsRecordedAndDoesNotBlockTheClosing() throws Exception {
        UUID orderId = finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));

        // a DIFFERENT collection for the already paid order: the Payment Service's report is accepted and recorded
        payWith(orderId, "PAID", 10, attemptOf(orderId), UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationRequired").value(true));

        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.scopeDrift").value(true))
                .andExpect(jsonPath("$.driftBlockers[0]").value("RECONCILIATION_REQUIRED"));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT outcome_at_confirm FROM table_session_closure_drifts"
                + " WHERE operation_id = ?", String.class, operation));
    }

    @Test
    void notClosedAfterAChangeStillReleasesTheHoldWithoutADrift() throws Exception {
        UUID orderId = finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        jdbc.update("UPDATE orders SET current_due_minor = 100 WHERE id = ?", orderId);

        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.scopeDrift").value(false));

        assertEquals(0, count("table_session_closure_drifts"), "the table did not close, so nothing drifted");
    }

    @Test
    void confirmingAnUnknownOrForeignOperationIsNotFoundOrAHarmlessAbort() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        // closing something that was never prepared for this restaurant and session is not possible
        confirm(UUID.randomUUID(), "CLOSED").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOSURE_NOT_FOUND"));
        confirm(QR, DevFixtures.TABLE_SESSION_B1, operation, RESTAURANT, "CLOSED").andExpect(status().isNotFound());
        confirm(QR, SESSION, operation, DevFixtures.RESTAURANT_B, "CLOSED").andExpect(status().isNotFound());
        // aborting something this Order Service holds nothing for is a safe "nothing to abort" and touches nothing
        confirm(QR, DevFixtures.TABLE_SESSION_B1, operation, RESTAURANT, "NOT_CLOSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("NOT_STARTED"));
        lookup(operation).andExpect(jsonPath("$.state").value("PREPARED"));
    }
}
