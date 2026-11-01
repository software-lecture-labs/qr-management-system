package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ORDER-03 task 3: staff drive the fulfillment lifecycle; who may, in which order, and what is recorded. */
class OrderFulfillmentApiTests extends AbstractOrderStatusApiTest {

    private static final String A1 = DevFixtures.TOKEN_A1;
    private static final String KITCHEN = staff(DevFixtures.STAFF_KITCHEN_A);
    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);

    @Test
    void theWholeLifecycleRunsInOrderWithTheRightRoleAtEachStep() throws Exception {
        UUID orderId = placeSaladOrder(A1);

        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousFulfillmentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.changed").value(true));
        patchStatus(KITCHEN, orderId, "READY").andExpect(status().isOk());
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isOk());
        patchStatus(ADMIN, orderId, "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("COMPLETED"));

        assertEquals("COMPLETED", fulfillmentOf(orderId));
        assertEquals("NOT_REQUESTED", paymentOf(orderId), "completing an order does not touch payment");
        assertEquals(List.of("-" + ">RECEIVED|GUEST|CHECKOUT", "RECEIVED>PREPARING|STAFF|STAFF_API",
                "PREPARING>READY|STAFF|STAFF_API", "READY>SERVED|STAFF|STAFF_API",
                "SERVED>COMPLETED|STAFF|STAFF_API"), fulfillmentHistory(orderId));
    }

    @Test
    void everyChangeWritesOneOutboxEventAndBumpsTheVersion() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        long before = versionOf(orderId);
        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isOk());
        assertEquals(before + 1, versionOf(orderId));
        assertEquals(1, changeEvents(orderId));
        // the Kitchen caused it, so it is not told about its own change
        assertEquals(List.of("NOTIFICATION", "WAITER"), destinationsOfChangeTo(orderId, "PREPARING"));

        patchStatus(KITCHEN, orderId, "READY").andExpect(status().isOk());
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isOk());
        assertEquals(List.of("KITCHEN", "NOTIFICATION", "WAITER"), destinationsOfChangeTo(orderId, "SERVED"));
        patchStatus(WAITER, orderId, "COMPLETED").andExpect(status().isOk());
        assertEquals(List.of("KITCHEN", "NOTIFICATION", "QR", "WAITER"), destinationsOfChangeTo(orderId, "COMPLETED"));

        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?"
                + " AND payload->>'toStatus' = 'READY'", String.class, orderId);
        JsonNode event = json.readTree(payload);
        assertEquals("PREPARING", event.path("fromStatus").asString());
        assertEquals("STAFF", event.path("actorType").asString());
        assertEquals("KITCHEN", event.path("actorRole").asString());
        assertFalse(payload.contains(DevFixtures.USER_KITCHEN_A.toString()), "no staff user id in an event");
    }

    // ---- who may set what -------------------------------------------------------------------------------------------

    @Test
    void eachRoleIsRefusedTheStatusesThatAreNotItsOwn() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        String[][] refused = {{DevFixtures.STAFF_KITCHEN_A, "SERVED"}, {DevFixtures.STAFF_KITCHEN_A, "COMPLETED"},
                {DevFixtures.STAFF_WAITER_A, "PREPARING"}, {DevFixtures.STAFF_WAITER_A, "READY"},
                {DevFixtures.STAFF_ADMIN_A, "PREPARING"}, {DevFixtures.STAFF_ADMIN_A, "READY"},
                {DevFixtures.STAFF_ADMIN_A, "RECEIVED"}};
        for (String[] attempt : refused) {
            patchStatus(staff(attempt[0]), orderId, attempt[1]).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertEquals("RECEIVED", fulfillmentOf(orderId));
        assertEquals(1, fulfillmentHistory(orderId).size(), "a refused change leaves no trace");
    }

    @Test
    void aGuestAndAnonymousCallersCanNeverChangeAStatus() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        patchStatus(guest(A1), orderId, "PREPARING").andExpect(status().isForbidden());
        patchStatus(null, orderId, "PREPARING").andExpect(status().isUnauthorized());
        patchStatus(staff("not-a-known-credential"), orderId, "PREPARING").andExpect(status().isUnauthorized());
        // a service credential is not staff authority either
        patchStatus(service(KITCHEN_CREDENTIAL), orderId, "PREPARING").andExpect(status().isForbidden());
        // identity hints in headers are not an authority
        mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/orders/" + orderId + "/fulfillment-status")
                        .contentType("application/json").content("{\"status\":\"PREPARING\"}")
                        .header("X-Role", "KITCHEN").header("X-User-Id", DevFixtures.USER_KITCHEN_A.toString())
                        .header("X-Restaurant-Id", DevFixtures.RESTAURANT_A.toString()))
                .andExpect(status().isUnauthorized());
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    @Test
    void anotherRestaurantsStaffSeesTheOrderAsMissing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        // an admin/waiter of restaurant B: status is theirs to set in principle, the order is not theirs
        patchStatus(staff(DevFixtures.STAFF_WAITER_B), orderId, "SERVED").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        patchStatus(staff(DevFixtures.STAFF_ADMIN_B), orderId, "COMPLETED").andExpect(status().isNotFound());
        patchStatus(WAITER, UUID.randomUUID(), "SERVED").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    // ---- the state machine --------------------------------------------------------------------------------------------

    @Test
    void stepsCannotBeSkippedAndNothingGoesBackwards() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        patchStatus(KITCHEN, orderId, "READY").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.details.currentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.details.allowedNext").value("PREPARING"));
        patchStatus(WAITER, orderId, "COMPLETED").andExpect(status().isConflict());
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isConflict());
        assertEquals("RECEIVED", fulfillmentOf(orderId));

        advanceTo(orderId, "READY");
        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isConflict());
        patchStatus(WAITER, orderId, "COMPLETED").andExpect(status().isConflict());
        assertEquals("READY", fulfillmentOf(orderId));
    }

    @Test
    void aCompletedOrderIsFinal() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "COMPLETED");
        for (String attempt : new String[]{"PREPARING", "READY"}) {
            patchStatus(KITCHEN, orderId, attempt).andExpect(status().isConflict());
        }
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isConflict());
        patchStatus(WAITER, orderId, "COMPLETED_AGAIN").andExpect(status().isUnprocessableContent());
        assertEquals("COMPLETED", fulfillmentOf(orderId));
    }

    @Test
    void cancellingIsNotPossibleThroughAStatusChange() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        for (String credential : new String[]{DevFixtures.STAFF_ADMIN_A, DevFixtures.STAFF_WAITER_A,
                DevFixtures.STAFF_KITCHEN_A}) {
            patchStatus(staff(credential), orderId, "CANCELLED").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        }
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    @Test
    void aCancelledOrderCannotBeMovedAnyFurther() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        // ORDER-04 will cancel through its own use case; here the row is cancelled directly to test the guard
        jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", orderId);
        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.allowedNext").value("NONE"));
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isConflict());
        patchStatus(WAITER, orderId, "COMPLETED").andExpect(status().isConflict());
    }

    // ---- repeats ----------------------------------------------------------------------------------------------------------

    @Test
    void repeatingTheStepYouJustMadeIsAHarmlessNoOp() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isOk());
        long version = versionOf(orderId);

        patchStatus(KITCHEN, orderId, "PREPARING").andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PREPARING"));

        assertEquals(version, versionOf(orderId));
        assertEquals(2, fulfillmentHistory(orderId).size(), "no second history row");
        assertEquals(1, changeEvents(orderId), "no second event");
    }

    @Test
    void theSameStatusReportedByAnotherAuthorisedPersonIsAlsoANoOp() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        long version = versionOf(orderId);
        int historyRows = fulfillmentHistory(orderId).size();
        int events = changeEvents(orderId);

        // another waiter and an admin report the very same result: no error, nothing written twice
        for (String other : new String[]{staff(registerSecondWaiter()), ADMIN}) {
            patchStatus(other, orderId, "SERVED").andExpect(status().isOk())
                    .andExpect(jsonPath("$.changed").value(false))
                    .andExpect(jsonPath("$.fulfillmentStatus").value("SERVED"));
        }
        assertEquals(version, versionOf(orderId));
        assertEquals(historyRows, fulfillmentHistory(orderId).size());
        assertEquals(events, changeEvents(orderId));

        // authority is still checked on a repeat: a role that may not set SERVED learns nothing from it
        patchStatus(KITCHEN, orderId, "SERVED").andExpect(status().isForbidden());
        // and an OLDER status is never a harmless repeat: it would move the order backwards
        patchStatus(WAITER, orderId, "COMPLETED").andExpect(status().isOk());
        patchStatus(WAITER, orderId, "SERVED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertEquals("COMPLETED", fulfillmentOf(orderId));
    }

    @Test
    void anAdminMayServeAndTheActionIsRecordedWithTheAdmin() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        patchStatus(ADMIN, orderId, "SERVED").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        assertEquals("SERVED", fulfillmentOf(orderId));
        assertEquals("ADMIN", jdbc.queryForObject("SELECT actor_role FROM order_status_history WHERE order_id = ?"
                + " AND to_status = 'SERVED'", String.class, orderId));
        assertEquals(DevFixtures.USER_ADMIN_A.toString(), jsonPath_actorOf(orderId, "SERVED"));
        patchStatus(ADMIN, orderId, "COMPLETED").andExpect(status().isOk());
        // an admin still cannot take over the Kitchen's statuses
        UUID other = placeSaladOrder(DevFixtures.TOKEN_A2);
        patchStatus(ADMIN, other, "PREPARING").andExpect(status().isForbidden());
    }

    private String jsonPath_actorOf(UUID orderId, String toStatus) {
        return jdbc.queryForObject("SELECT actor_id FROM order_status_history WHERE order_id = ? AND to_status = ?",
                String.class, orderId, toStatus);
    }

    // ---- input ------------------------------------------------------------------------------------------------------------

    @Test
    void badInputIsRejectedAndNothingChanges() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        patchStatus(KITCHEN, orderId, "DONE").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.parameter").value("status"));
        patchStatus(KITCHEN, orderId, Map.of("status", " ")).andExpect(status().isUnprocessableContent());
        patchStatus(KITCHEN, orderId, Map.of("status", "PREPARING", "reason", "x".repeat(301)))
                .andExpect(status().isUnprocessableContent());
        patchStatus(KITCHEN, orderId, Map.of("status", "PREPARING", "reason", "bad\u0007reason"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.parameter").value("reason"));
        // unknown fields (a client trying to pick actor or restaurant) are refused outright
        patchStatus(KITCHEN, orderId, Map.of("status", "PREPARING", "restaurantId", DevFixtures.RESTAURANT_B))
                .andExpect(status().isBadRequest());
        patchStatus(KITCHEN, orderId, Map.of("status", "PREPARING", "actorId", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/orders/not-a-uuid/fulfillment-status").header("Authorization", KITCHEN)
                        .contentType("application/json").content("{\"status\":\"PREPARING\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("RECEIVED", fulfillmentOf(orderId));
    }

    @Test
    void theReasonIsTrimmedAndStoredInTheHistory() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        patchStatus(KITCHEN, orderId, Map.of("status", "PREPARING", "reason", "  rush order  "))
                .andExpect(status().isOk());
        assertEquals("rush order", jdbc.queryForObject("SELECT reason FROM order_status_history WHERE order_id = ?"
                + " AND to_status = 'PREPARING'", String.class, orderId));
    }

    // ---- what readers see ---------------------------------------------------------------------------------------------------

    @Test
    void theListViewsAndTheHistoryFollowTheStatus() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        assertEquals(1, body(getWith(guest(A1), "/api/v1/orders?view=active")).path("totalItems").asInt());
        advanceTo(orderId, "COMPLETED");
        assertEquals(0, body(getWith(guest(A1), "/api/v1/orders?view=active")).path("totalItems").asInt());
        assertEquals(1, body(getWith(guest(A1), "/api/v1/orders?view=completed")).path("totalItems").asInt());
        // completed but never paid: still a completed order with an open payment axis
        assertEquals(1, body(getWith(guest(A1), "/api/v1/orders?view=completed&paymentStatus=NOT_REQUESTED"))
                .path("totalItems").asInt());

        JsonNode history = body(getWith(staff(DevFixtures.STAFF_ADMIN_A), "/api/v1/orders/" + orderId + "/history"));
        assertEquals(6, history.path("entries").size(), "two initial rows (one per axis) and four changes");
        JsonNode last = history.path("entries").get(5);
        assertEquals("COMPLETED", last.path("toStatus").asString());
        assertEquals(DevFixtures.USER_WAITER_A.toString(), last.path("actorId").asString(), "ADMIN sees who");
        JsonNode asGuest = body(getWith(guest(A1), "/api/v1/orders/" + orderId + "/history"));
        assertTrue(asGuest.path("entries").get(5).path("actorId").isNull()
                || asGuest.path("entries").get(5).path("actorId").isMissingNode(), "a guest never sees staff ids");
    }

    // ---- the database repeats the rules --------------------------------------------------------------------------------

    @Test
    void theDatabaseItselfRefusesIllegalFulfillmentMoves() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        assertGuardRefuses(orderId, "READY");
        assertGuardRefuses(orderId, "COMPLETED");
        jdbc.update("UPDATE orders SET fulfillment_status = 'PREPARING' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "RECEIVED");
        assertGuardRefuses(orderId, "SERVED");
        jdbc.update("UPDATE orders SET fulfillment_status = 'READY' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "CANCELLED");
        jdbc.update("UPDATE orders SET fulfillment_status = 'SERVED' WHERE id = ?", orderId);
        jdbc.update("UPDATE orders SET fulfillment_status = 'COMPLETED' WHERE id = ?", orderId);
        assertGuardRefuses(orderId, "SERVED");
        assertGuardRefuses(orderId, "CANCELLED");
        // writing the same value, or other columns, is not a move
        assertEquals(1, jdbc.update("UPDATE orders SET fulfillment_status = 'COMPLETED' WHERE id = ?", orderId));
    }

    @Test
    void cancellingFromReceivedOrPreparingIsLeftOpenForTheCancellationUseCase() throws Exception {
        UUID received = placeSaladOrder(A1);
        assertEquals(1, jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", received));
        UUID preparing = placeSaladOrder(DevFixtures.TOKEN_A2);
        advanceTo(preparing, "PREPARING");
        assertEquals(1, jdbc.update("UPDATE orders SET fulfillment_status = 'CANCELLED' WHERE id = ?", preparing));
    }

    private void assertGuardRefuses(UUID orderId, String target) {
        DataAccessException error = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE orders SET fulfillment_status = ? WHERE id = ?", target, orderId), target);
        assertTrue(error.getMessage().contains("invalid fulfillment transition"), error.getMessage());
    }
}
