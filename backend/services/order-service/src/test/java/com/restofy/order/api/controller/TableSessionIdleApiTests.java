package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001 K6 (Order side): the QR Service asks to expire an idle table session or idle guests, and reads what the
 * orders of the session mean for its idle rules. Orders protect their guest and the main session; a safely completed
 * cancellation releases them; uncertain money never does.
 */
class TableSessionIdleApiTests extends AbstractClosureApiTest {

    private static final Instant EXPIRED = Instant.now().minusSeconds(120);
    private static final Instant NOT_YET = Instant.now().plusSeconds(900);

    private List<String> idleGuestsOf(JsonNode answer) {
        List<String> guests = new ArrayList<>();
        answer.path("idleGuestIds").forEach(g -> guests.add(g.asString()));
        return guests;
    }

    private List<String> protectedGuestsOf(JsonNode state) {
        List<String> guests = new ArrayList<>();
        state.path("protectedGuests").forEach(g -> guests.add(g.asString()));
        return guests;
    }

    // ---- expiring an idle session -------------------------------------------------------------------------------------

    @Test
    void aSessionWithoutOrdersWhoseMainWindowEndedMayExpireAsAWhole() throws Exception {
        UUID operation = UUID.randomUUID();

        idle(operation, EXPIRED, idleGuest(DevFixtures.GUEST_A1, EXPIRED), idleGuest(DevFixtures.GUEST_A2, EXPIRED))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("IDLE"))
                .andExpect(jsonPath("$.state").value("PREPARED")).andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"))
                .andExpect(jsonPath("$.effect").value("IDLE_MAIN")).andExpect(jsonPath("$.checkoutFenced").value(true))
                .andExpect(jsonPath("$.idleGuestIds").isEmpty());

        assertEquals("IDLE_MAIN", jdbc.queryForObject("SELECT effect FROM table_session_closures", String.class));
        // while the permit is held nobody can slip an order in
        orderAgain(DevFixtures.TOKEN_A1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict());

        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", receipt("IDLE_MAIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
        // the session ended: no further operation for it
        prepare(UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_IN_PROGRESS"));
    }

    @Test
    void onlyTheIdleGuestsExpireWhenTheMainWindowHasNotEnded() throws Exception {
        UUID operation = UUID.randomUUID();

        JsonNode answer = body(idle(operation, NOT_YET, idleGuest(DevFixtures.GUEST_A1, EXPIRED),
                idleGuest(DevFixtures.GUEST_A2, NOT_YET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"))
                .andExpect(jsonPath("$.effect").value("IDLE_GUESTS")));

        assertEquals(List.of(DevFixtures.GUEST_A1.toString()), idleGuestsOf(answer));
        // only the guest that is about to expire is kept from ordering; the other guest still can
        orderAgain(DevFixtures.TOKEN_A1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());

        confirmRaw(QR, SESSION, operation, RESTAURANT, "CLOSED", receipt("IDLE_GUESTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
        // expiring guests ended nothing: the session goes on and may be closed or expired later
        prepare(UUID.randomUUID()).andExpect(status().isOk());
        assertEquals(1, closures(), "the settled command was not authorised, so nothing else is stored");
    }

    @Test
    void aGuestWhoHasAnOrderIsNotExpiredButTheOthersAre() throws Exception {
        placeSaladOrder(DevFixtures.TOKEN_A1);

        JsonNode answer = body(idle(UUID.randomUUID(), EXPIRED, idleGuest(DevFixtures.GUEST_A1, EXPIRED),
                idleGuest(DevFixtures.GUEST_A2, EXPIRED)).andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("IDLE_GUESTS")));

        assertEquals(List.of(DevFixtures.GUEST_A2.toString()), idleGuestsOf(answer),
                "the order protects its guest, and with them the whole session");
    }

    @Test
    void whenEveryIdleGuestIsProtectedNothingMayExpire() throws Exception {
        placeSaladOrder(DevFixtures.TOKEN_A1);

        var blocked = idle(UUID.randomUUID(), EXPIRED, idleGuest(DevFixtures.GUEST_A1, EXPIRED))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.sourceStatus").value("BLOCKED")).andExpect(jsonPath("$.effect").value("NONE"))
                .andExpect(jsonPath("$.checkoutFenced").value(false));

        assertEquals(List.of("SESSION_PROTECTED_BY_ORDERS"), blockersOf(blocked));
        assertEquals(0, closures());
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    @Test
    void aSettledSessionIsClosedNotExpiredIdle() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);

        var blocked = idle(UUID.randomUUID(), EXPIRED, idleGuest(DevFixtures.GUEST_A1, EXPIRED))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("BLOCKED"));

        assertEquals(List.of("SESSION_PROTECTED_BY_ORDERS"), blockersOf(blocked));
        assertEquals(0, closures());
    }

    @Test
    void nothingExpiresWhenNothingIsIdle() throws Exception {
        var blocked = idle(UUID.randomUUID(), NOT_YET, idleGuest(DevFixtures.GUEST_A1, NOT_YET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("BLOCKED"));

        assertEquals(List.of("NOTHING_TO_EXPIRE"), blockersOf(blocked));
        assertEquals(0, closures());
    }

    @Test
    void anIdleCommandNeedsItsDetails() throws Exception {
        var body = settledBody(RESTAURANT, UUID.randomUUID(), DIGEST);
        body.put("purpose", "IDLE");

        prepareRaw(QR, SESSION, body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertEquals(0, closures());
    }

    @Test
    void anIdleOperationReplaysLikeAnyOther() throws Exception {
        UUID operation = UUID.randomUUID();
        var body = idleBody(operation, Instant.now(), EXPIRED, List.of(idleGuest(DevFixtures.GUEST_A1, EXPIRED)));
        prepareRaw(QR, SESSION, body).andExpect(jsonPath("$.state").value("PREPARED"));

        prepareRaw(QR, SESSION, body).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.effect").value("IDLE_MAIN"));
        lookup(operation).andExpect(jsonPath("$.sourceStatus").value("AUTHORISED"));
        assertEquals(1, closures());
        // aborting releases the hold and orders are accepted again
        confirm(operation, "NOT_CLOSED").andExpect(jsonPath("$.state").value("RELEASED"));
        orderAgain(DevFixtures.TOKEN_A1).andExpect(status().isCreated());
    }

    // ---- what the orders mean for the idle rules ----------------------------------------------------------------------

    @Test
    void aSessionWithoutOrdersHasNoOrdersAndProtectsNobody() throws Exception {
        JsonNode state = body(orderState().andExpect(status().isOk()).andExpect(jsonPath("$.knowledge").value("NO_ORDERS"))
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT.toString()))
                .andExpect(jsonPath("$.tableSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.protectedGuests").isEmpty()).andExpect(jsonPath("$.mainCancellationAt").isEmpty()));

        assertTrue(state.path("revision").asLong() >= 1);
        assertEquals(Duration.ofMinutes(1), Duration.between(Instant.parse(state.path("verifiedAt").asString()),
                Instant.parse(state.path("validUntil").asString())));
        assertTrue(state.path("cancellationReleases").isEmpty());
    }

    @Test
    void anUnfinishedOrderProtectsItsGuestAndTheSession() throws Exception {
        placeSaladOrder(DevFixtures.TOKEN_A1);

        JsonNode state = body(orderState().andExpect(jsonPath("$.knowledge").value("OPEN_WORK")));

        assertEquals(List.of(DevFixtures.GUEST_A1.toString()), protectedGuestsOf(state));
    }

    @Test
    void aSettledSessionIsReportedAsSettledAndItsGuestsStayProtected() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);

        JsonNode state = body(orderState().andExpect(jsonPath("$.knowledge").value("SETTLED")));

        assertEquals(List.of(DevFixtures.GUEST_A1.toString()), protectedGuestsOf(state));
    }

    @Test
    void aSafelyCancelledOrderReleasesItsGuestAndTheSessionAndSaysSince() throws Exception {
        UUID cancelled = placeSaladOrder(DevFixtures.TOKEN_A1);
        cancelAsGuest(cancelled, DevFixtures.TOKEN_A1);

        JsonNode state = body(orderState().andExpect(jsonPath("$.knowledge").value("NO_ORDERS"))
                .andExpect(jsonPath("$.protectedGuests").isEmpty()).andExpect(jsonPath("$.mainCancellationAt").isNotEmpty()));

        assertTrue(state.path("cancellationReleases").has(DevFixtures.GUEST_A1.toString()));
        // and with nothing protecting it, the session may now expire idle (the QR Service counts a fresh window)
        idle(UUID.randomUUID(), EXPIRED, idleGuest(DevFixtures.GUEST_A1, EXPIRED)).andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("IDLE_MAIN"));
    }

    @Test
    void aGuestIsReleasedButTheMainSessionStaysProtectedWhileAnotherGuestHasAnOrder() throws Exception {
        UUID cancelled = placeSaladOrder(DevFixtures.TOKEN_A1);
        cancelAsGuest(cancelled, DevFixtures.TOKEN_A1);
        placeSaladOrder(DevFixtures.TOKEN_A2);

        JsonNode state = body(orderState().andExpect(jsonPath("$.knowledge").value("OPEN_WORK"))
                .andExpect(jsonPath("$.mainCancellationAt").isEmpty()));

        assertEquals(List.of(DevFixtures.GUEST_A2.toString()), protectedGuestsOf(state));
        assertTrue(state.path("cancellationReleases").has(DevFixtures.GUEST_A1.toString()));
        assertFalse(state.path("cancellationReleases").has(DevFixtures.GUEST_A2.toString()));
    }

    @Test
    void aRefundThatIsStillOwedKeepsTheGuestProtected() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());
        // a waiter cancels the paid order: the refund it requires is only recorded, never reported as done
        cancelAsWaiter(orderId);

        JsonNode state = body(orderState().andExpect(jsonPath("$.knowledge").value("OPEN_WORK"))
                .andExpect(jsonPath("$.mainCancellationAt").isEmpty()));

        assertEquals(List.of(DevFixtures.GUEST_A1.toString()), protectedGuestsOf(state));
        assertTrue(state.path("cancellationReleases").isEmpty(), "uncertain money never releases anybody");
    }

    @Test
    void theOrderStateOfAnotherRestaurantIsNeverMixedIn() throws Exception {
        placeSaladOrder(DevFixtures.TOKEN_A1);

        orderState(QR, SESSION, DevFixtures.RESTAURANT_B).andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledge").value("NO_ORDERS")).andExpect(jsonPath("$.protectedGuests").isEmpty());
    }

    @Test
    void onlyTheQrServiceMayReadTheOrderState() throws Exception {
        orderState(null, SESSION, RESTAURANT).andExpect(status().isUnauthorized());
        orderState(guest(DevFixtures.TOKEN_A1), SESSION, RESTAURANT).andExpect(status().isForbidden());
        orderState(staff(DevFixtures.STAFF_ADMIN_A), SESSION, RESTAURANT).andExpect(status().isForbidden());
        orderState(service(KITCHEN_CREDENTIAL), SESSION, RESTAURANT).andExpect(status().isForbidden());
        orderState(service(PAYMENT_CREDENTIAL), SESSION, RESTAURANT).andExpect(status().isForbidden());
        orderState(QR, SESSION, RESTAURANT).andExpect(status().isOk());
    }
}
