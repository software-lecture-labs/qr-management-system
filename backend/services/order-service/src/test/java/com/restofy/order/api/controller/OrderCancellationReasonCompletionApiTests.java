package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 3 (a waiter gives the reason afterwards) and reading the cancellations of an order. */
class OrderCancellationReasonCompletionApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String KITCHEN = staff(DevFixtures.STAFF_KITCHEN_A);

    private ResultActions completeReason(String authorization, UUID orderId,
                                                                               UUID cancellationId, Object body)
            throws Exception {
        var request = patch("/api/v1/orders/" + orderId + "/cancellations/" + cancellationId + "/reason")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private UUID cancelledWithoutReason(UUID orderId) throws Exception {
        return requestIdOf(cancelAll(guest(A1), orderId).andExpect(status().isCreated()));
    }

    // ---- the waiter's completion ------------------------------------------------------------------------------------

    @Test
    void aWaiterGivesTheMissingReasonAfterwardsAndItIsAudited() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].reasonPending").value(true));

        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("WAIT_TOO_LONG"),
                "reasonText", "Table 4 left")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonPending").value(false))
                .andExpect(jsonPath("$.reasons[*].source", contains("STAFF", "STAFF")))
                .andExpect(jsonPath("$.reasons[0].code").value("WAIT_TOO_LONG"))
                .andExpect(jsonPath("$.reasons[1].text").value("Table 4 left"))
                .andExpect(jsonPath("$.reasons[0].addedBy.role").value("WAITER"))
                .andExpect(jsonPath("$.reasons[0].addedBy.id").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT staff_reason_added_by, staff_reason_added_at, version"
                + " FROM order_cancellations WHERE id = ?", cancellationId);
        assertEquals(DevFixtures.USER_WAITER_A.toString(), row.get("staff_reason_added_by"));
        assertEquals(true, row.get("staff_reason_added_at") != null);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE added_by_type = 'STAFF'"
                + " AND added_by_id = ? AND added_by_role = 'WAITER'", Integer.class,
                DevFixtures.USER_WAITER_A.toString()));
        // the guest sees it too
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].reasonPending").value(false))
                .andExpect(jsonPath("$.cancellations[0].reasons.length()").value(2));
    }

    @Test
    void theReasonCanOnlyBeGivenOnceAndNeverOverwritten() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var first = Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE"));
        completeReason(WAITER, orderId, cancellationId, first).andExpect(status().isOk());

        // the very same completion again is a harmless retry
        completeReason(WAITER, orderId, cancellationId, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons.length()").value(1));
        // anything else is refused: no overwrite, no addition, by the same or another waiter
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("OTHER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE"),
                "reasonText", "more")).andExpect(status().isConflict());
        completeReason(staff(registerSecondWaiter()), orderId, cancellationId, first)
                .andExpect(status().isConflict());

        assertEquals(1, count("order_cancellation_reasons"));
        assertEquals(DevFixtures.USER_WAITER_A.toString(), jdbc.queryForObject(
                "SELECT staff_reason_added_by FROM order_cancellations WHERE id = ?", String.class, cancellationId));
    }

    @Test
    void aWaiterCannotAddToAReasonTheCustomerGave() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = requestIdOf(cancel(guest(A1), orderId, newKey(),
                cancelAllWith(List.of("CHANGED_MIND"), null)).andExpect(status().isCreated()));

        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "my own idea"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        assertEquals(1, count("order_cancellation_reasons"));
        assertEquals(null, jdbc.queryForObject("SELECT staff_reason_added_at FROM order_cancellations WHERE id = ?",
                Timestamp.class, cancellationId));
    }

    @Test
    void theReasonCanBeCompletedForARequestStillWaitingAndForARejectedOne() throws Exception {
        UUID waitingOrder = placeMixedOrder(A1);
        advanceTo(waitingOrder, "PREPARING");
        UUID waiting = requestIdOf(cancelAll(guest(A1), waitingOrder).andExpect(status().isCreated()));
        completeReason(WAITER, waitingOrder, waiting, Map.of("reasonCodes", List.of("KITCHEN_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"));
        kitchenDecides(waitingOrder, waiting, "REJECTED", "KITCHEN_DECLINED").andExpect(status().isOk());
        assertEquals("REJECTED", cancellationStatus(waiting));
        completeReason(WAITER, waitingOrder, waiting, Map.of("reasonCodes", List.of("KITCHEN_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // the reason of a REJECTED request given later never changes the decision
        UUID second = placeMixedOrder(DevFixtures.TOKEN_A2);
        advanceTo(second, "PREPARING");
        UUID other = requestIdOf(cancelAll(guest(DevFixtures.TOKEN_A2), second).andExpect(status().isCreated()));
        kitchenDecides(second, other, "REJECTED", "ALREADY_COOKED").andExpect(status().isOk());
        completeReason(WAITER, second, other, Map.of("reasonText", "customer insisted")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.code").value("ALREADY_COOKED"));
    }

    @Test
    void anEmptyOrInvalidCompletionIsRefusedAndWritesNothing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);

        completeReason(WAITER, orderId, cancellationId, Map.of()).andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "   ")).andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("NOPE")))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("OTHER", "OTHER")))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "x".repeat(301)))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "bell\u0007"))
                .andExpect(status().isUnprocessableContent());

        assertEquals(0, count("order_cancellation_reasons"));
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "fine now")).andExpect(status().isOk());
    }

    @Test
    void onlyAWaiterOrAdminOfTheRestaurantMayCompleteTheReason() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var body = Map.of("reasonText", "late reason");

        completeReason(null, orderId, cancellationId, body).andExpect(status().isUnauthorized());
        completeReason(guest(A1), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(KITCHEN, orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(service(KITCHEN_CREDENTIAL), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(staff(DevFixtures.STAFF_WAITER_B), orderId, cancellationId, body).andExpect(status().isNotFound());
        completeReason(staff(DevFixtures.STAFF_ADMIN_B), orderId, cancellationId, body).andExpect(status().isNotFound());
        completeReason(WAITER, UUID.randomUUID(), cancellationId, body).andExpect(status().isNotFound());
        completeReason(WAITER, orderId, UUID.randomUUID(), body).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_FOUND"));
        // a cancellation of another order of the same restaurant is not reachable through this order
        UUID otherOrder = placeSaladOrder(DevFixtures.TOKEN_A2);
        completeReason(WAITER, otherOrder, cancellationId, body).andExpect(status().isNotFound());

        assertEquals(0, count("order_cancellation_reasons"));
    }

    @Test
    void anAdminGivesTheMissingReasonAndItIsAuditedAsTheAdmins() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);

        completeReason(ADMIN, orderId, cancellationId, Map.of("reasonCodes", List.of("ORDERED_BY_MISTAKE"),
                "reasonText", "called the guest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonPending").value(false))
                .andExpect(jsonPath("$.reasons[*].source", contains("STAFF", "STAFF")))
                .andExpect(jsonPath("$.reasons[0].addedBy.role").value("ADMIN"))
                .andExpect(jsonPath("$.reasons[0].addedBy.id").value(DevFixtures.USER_ADMIN_A.toString()));

        assertEquals(DevFixtures.USER_ADMIN_A.toString(), jdbc.queryForObject(
                "SELECT staff_reason_added_by FROM order_cancellations WHERE id = ?", String.class, cancellationId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE added_by_type = 'STAFF'"
                + " AND added_by_role = 'ADMIN' AND added_by_id = ? AND added_at IS NOT NULL", Integer.class,
                DevFixtures.USER_ADMIN_A.toString()));
        // the waiter does not get to add to or replace what the admin gave; the admin's retry is harmless
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "my version")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        completeReason(ADMIN, orderId, cancellationId, Map.of("reasonCodes", List.of("ORDERED_BY_MISTAKE"),
                "reasonText", "called the guest")).andExpect(status().isOk());
        completeReason(ADMIN, orderId, cancellationId, Map.of("reasonText", "changed my mind about it"))
                .andExpect(status().isConflict());
        assertEquals(2, count("order_cancellation_reasons"));
    }

    @Test
    void anAdminCannotRewriteTheCustomersTheWaitersOrTheKitchensReason() throws Exception {
        // the customer's reason
        UUID byGuest = placeSaladOrder(A1);
        UUID guestCancellation = requestIdOf(cancel(guest(A1), byGuest, newKey(), cancelAllWith(List.of("CHANGED_MIND"), null))
                .andExpect(status().isCreated()));
        completeReason(ADMIN, byGuest, guestCancellation, Map.of("reasonText", "no, it was something else"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        // a waiter's reason given at cancellation time
        UUID byWaiter = placeSaladOrder(DevFixtures.TOKEN_A2);
        UUID waiterCancellation = requestIdOf(cancel(WAITER, byWaiter, newKey(), cancelAllWith(List.of("OTHER"), "mine"))
                .andExpect(status().isCreated()));
        completeReason(ADMIN, byWaiter, waiterCancellation, Map.of("reasonText", "no, mine now"))
                .andExpect(status().isConflict());
        // a Kitchen's own request, whose reason is mandatory
        UUID byKitchen = placeMixedOrder(A1);
        var ask = new HashMap<String, Object>();
        ask.put("eventId", UUID.randomUUID());
        ask.put("restaurantId", DevFixtures.RESTAURANT_A);
        ask.put("orderId", byKitchen);
        ask.put("cancelAll", true);
        ask.put("reasonCodes", List.of("ITEM_UNAVAILABLE"));
        mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/internal/order-events/kitchen-cancellation-request")
                        .contentType("application/json").content(toJson(ask))
                        .header("Authorization", service(KITCHEN_CREDENTIAL))).andExpect(status().isOk());
        UUID kitchenCancellation = jdbc.queryForObject("SELECT id FROM order_cancellations WHERE order_id = ?", UUID.class,
                byKitchen);
        completeReason(ADMIN, byKitchen, kitchenCancellation, Map.of("reasonText", "no, the admin says so"))
                .andExpect(status().isConflict());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE cancellation_id = ?",
                Integer.class, guestCancellation));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE cancellation_id = ?",
                Integer.class, waiterCancellation));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE cancellation_id = ?",
                Integer.class, kitchenCancellation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_cancellations WHERE staff_reason_added_at IS NOT NULL",
                Integer.class), "nobody completed anything");
    }

    @Test
    void aStaffMemberNamedByTheKitchenIsNotTheAuthorOfWhatTheKitchenSent() throws Exception {
        // the Kitchen claims it acts for the administrator and for the waiter; that is information, not identity
        for (UUID claimed : List.of(DevFixtures.USER_ADMIN_A, DevFixtures.USER_WAITER_A)) {
            UUID orderId = placeSaladOrder(A1);
            var ask = new HashMap<String, Object>();
            ask.put("eventId", UUID.randomUUID());
            ask.put("restaurantId", DevFixtures.RESTAURANT_A);
            ask.put("orderId", orderId);
            ask.put("cancelAll", true);
            ask.put("reasonCodes", List.of("ITEM_UNAVAILABLE"));
            ask.put("requestedByStaffId", claimed);
            mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/internal/order-events/kitchen-cancellation-request")
                            .contentType("application/json").content(toJson(ask))
                            .header("Authorization", service(KITCHEN_CREDENTIAL))).andExpect(status().isOk());
            UUID cancellationId = jdbc.queryForObject("SELECT id FROM order_cancellations WHERE order_id = ?", UUID.class,
                    orderId);

            // nobody is treated as the author of a reason that merely names them: not even a "retry" of the same words
            completeReason(ADMIN, orderId, cancellationId, Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
            completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE")))
                    .andExpect(status().isConflict());

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_cancellations WHERE order_id = ?"
                    + " AND (requested_by_id = ? OR staff_reason_added_by IS NOT NULL)", Integer.class, orderId,
                    claimed.toString()), "the claimed person is neither requester nor completer");
            // the claim sits in its own column; the author of the request and of the reason is the Kitchen service
            assertEquals(claimed, jdbc.queryForObject("SELECT reported_staff_id FROM order_cancellations WHERE id = ?",
                    UUID.class, cancellationId));
            assertEquals("SERVICE", jdbc.queryForObject("SELECT requested_by_type FROM order_cancellations WHERE id = ?",
                    String.class, cancellationId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE cancellation_id = ?"
                    + " AND source = 'SERVICE' AND added_by_type = 'SERVICE' AND added_by_id = 'KITCHEN'"
                    + " AND added_by_role = 'KITCHEN'", Integer.class, cancellationId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE cancellation_id = ?"
                    + " AND added_by_id = ?", Integer.class, cancellationId, claimed.toString()),
                    "the claimed person authored no reason");
            getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations")
                    .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("SERVICE"))
                    .andExpect(jsonPath("$.cancellations[0].reasons[0].addedBy.type").value("SERVICE"));
        }
    }

    @Test
    void theAdminsAuthorityIsNotSharedWithOtherRoles() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var body = Map.of("reasonText", "late reason");

        completeReason(KITCHEN, orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(guest(A1), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(service(KITCHEN_CREDENTIAL), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(service(PAYMENT_CREDENTIAL), orderId, cancellationId, body).andExpect(status().isForbidden());
        assertEquals(0, count("order_cancellation_reasons"));
    }

    @Test
    void anAdminAndAWaiterCompletingAtOnceLeaveExactlyOne() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var admin = pool.submit(() -> {
                start.await();
                return completeReason(ADMIN, orderId, cancellationId, Map.of("reasonText", "from the admin"))
                        .andReturn().getResponse().getStatus();
            });
            var waiter = pool.submit(() -> {
                start.await();
                return completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "from the waiter"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(admin.get(30, TimeUnit.SECONDS),
                    waiter.get(30, TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("order_cancellation_reasons"));
    }

    @Test
    void twoWaitersCompletingTheReasonAtOnceLeaveExactlyOne() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        String second = staff(registerSecondWaiter());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> {
                start.await();
                return completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "from the first waiter"))
                        .andReturn().getResponse().getStatus();
            });
            var two = pool.submit(() -> {
                start.await();
                return completeReason(second, orderId, cancellationId, Map.of("reasonText", "from the second waiter"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(one.get(30, TimeUnit.SECONDS),
                    two.get(30, TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("order_cancellation_reasons"));
    }

    // ---- reading the cancellations ----------------------------------------------------------------------------------

    @Test
    void theCancellationsOfAnOrderAreListedOldestFirstForTheOwnerAndStaff() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1))
                .andExpect(status().isCreated());
        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated());

        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.cancellations.length()").value(2))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("GUEST"))
                .andExpect(jsonPath("$.cancellations[1].requestedBy.type").value("STAFF"))
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").doesNotExist());
        getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").doesNotExist());
        getWith(ADMIN, "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").value(DevFixtures.USER_WAITER_A.toString()))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.id").doesNotExist());
    }

    @Test
    void aOrderWithoutCancellationsHasAnEmptyList() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations.length()").value(0));
    }

    @Test
    void theListIsClosedToOthers() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelledWithoutReason(orderId);
        String path = "/api/v1/orders/" + orderId + "/cancellations";

        getWith(null, path).andExpect(status().isUnauthorized());
        getWith(guest(DevFixtures.TOKEN_A2), path).andExpect(status().isNotFound());
        getWith(guest(DevFixtures.TOKEN_B1), path).andExpect(status().isNotFound());
        getWith(staff(DevFixtures.STAFF_WAITER_B), path).andExpect(status().isNotFound());
        getWith(KITCHEN, path).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getWith(service(KITCHEN_CREDENTIAL), path).andExpect(status().isForbidden());
        getWith(WAITER, "/api/v1/orders/" + UUID.randomUUID() + "/cancellations").andExpect(status().isNotFound());

        qr.register(A1, DevFixtures.guestA1(SessionState.CLOSED));
        getWith(guest(A1), path).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        getWith(WAITER, path).andExpect(status().isOk());
    }
}
