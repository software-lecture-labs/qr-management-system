package com.restofy.order.domain.closure;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-QR-001 K6: what the orders of a session mean for its idle rules, without any database. */
class OrderSessionScopeTest {

    private static final UUID GUEST_1 = UUID.randomUUID();
    private static final UUID GUEST_2 = UUID.randomUUID();
    private static final Instant EARLIER = Instant.parse("2026-10-31T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-31T10:30:00Z");

    private static OrderClosureFacts order(UUID guest, FulfillmentStatus fulfillment, PaymentStatus payment) {
        return new OrderClosureFacts(UUID.randomUUID(), fulfillment, payment, 0, false, 0, false, false, guest, null);
    }

    private static OrderClosureFacts cancelled(UUID guest, Instant at) {
        return new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED, 0, false, 0,
                false, false, guest, at);
    }

    @Test
    void aSessionWithoutOrdersProtectsNobodyAndNothingWasCancelled() {
        var scope = OrderSessionScope.of(List.of());

        assertEquals(OrderSessionScope.Knowledge.NO_ORDERS, scope.knowledge());
        assertTrue(scope.protectedGuests().isEmpty());
        assertTrue(scope.cancellationReleases().isEmpty());
        assertNull(scope.mainCancellationAt());
        assertFalse(scope.protectsMain());
    }

    @Test
    void anOrderThatIsNotSafelyCancelledProtectsItsGuestAndTheSession() {
        var scope = OrderSessionScope.of(List.of(order(GUEST_1, FulfillmentStatus.RECEIVED, PaymentStatus.NOT_REQUESTED)));

        assertEquals(OrderSessionScope.Knowledge.OPEN_WORK, scope.knowledge());
        assertEquals(Set.of(GUEST_1), scope.protectedGuests());
        assertTrue(scope.protectsMain());
        assertNull(scope.mainCancellationAt());
    }

    @Test
    void aSettledSessionIsSettledAndItsGuestsAreProtected() {
        var scope = OrderSessionScope.of(List.of(order(GUEST_1, FulfillmentStatus.COMPLETED, PaymentStatus.PAID),
                order(GUEST_2, FulfillmentStatus.COMPLETED, PaymentStatus.PAID)));

        assertEquals(OrderSessionScope.Knowledge.SETTLED, scope.knowledge());
        assertEquals(Set.of(GUEST_1, GUEST_2), scope.protectedGuests());
        assertTrue(scope.protectsMain());
    }

    @Test
    void whenEveryOrderIsSafelyCancelledNobodyIsProtectedAndTheLatestCancellationReleasesTheMainSession() {
        var scope = OrderSessionScope.of(List.of(cancelled(GUEST_1, EARLIER), cancelled(GUEST_2, LATER)));

        assertEquals(OrderSessionScope.Knowledge.NO_ORDERS, scope.knowledge());
        assertTrue(scope.protectedGuests().isEmpty());
        assertEquals(Map.of(GUEST_1, EARLIER, GUEST_2, LATER), scope.cancellationReleases());
        assertEquals(LATER, scope.mainCancellationAt());
        assertFalse(scope.protectsMain());
    }

    @Test
    void aGuestWhoseOrdersAreAllSafelyCancelledIsReleasedEvenIfAnotherGuestIsProtected() {
        var scope = OrderSessionScope.of(List.of(cancelled(GUEST_1, EARLIER),
                order(GUEST_2, FulfillmentStatus.PREPARING, PaymentStatus.NOT_REQUESTED)));

        assertEquals(OrderSessionScope.Knowledge.OPEN_WORK, scope.knowledge());
        assertEquals(Set.of(GUEST_2), scope.protectedGuests());
        assertEquals(Map.of(GUEST_1, EARLIER), scope.cancellationReleases());
        assertNull(scope.mainCancellationAt(), "the main session is protected while any guest is");
    }

    @Test
    void aGuestWithOneCancelledAndOneLiveOrderIsProtectedAndNotReleased() {
        var scope = OrderSessionScope.of(List.of(cancelled(GUEST_1, EARLIER),
                order(GUEST_1, FulfillmentStatus.RECEIVED, PaymentStatus.NOT_REQUESTED)));

        assertEquals(Set.of(GUEST_1), scope.protectedGuests());
        assertTrue(scope.cancellationReleases().isEmpty());
    }

    @Test
    void uncertainMoneyNeverReleasesAnybody() {
        var refundOwed = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.PAID, 0,
                false, 9000, false, false, GUEST_1, EARLIER);
        var paymentInFlight = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED,
                PaymentStatus.PENDING, 0, false, 0, false, false, GUEST_2, EARLIER);
        var unreconciled = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.PAID, 0,
                false, 0, false, true, UUID.randomUUID(), EARLIER);

        var scope = OrderSessionScope.of(List.of(refundOwed, paymentInFlight, unreconciled));

        assertEquals(OrderSessionScope.Knowledge.OPEN_WORK, scope.knowledge());
        assertEquals(3, scope.protectedGuests().size());
        assertTrue(scope.cancellationReleases().isEmpty());
        assertNull(scope.mainCancellationAt());
    }

    @Test
    void aCancelledOrderWithoutAnAppliedCancellationOrWithAnUndecidedOneStaysProtecting() {
        var noRecord = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED, 0,
                false, 0, false, false, GUEST_1, null);
        var undecided = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED, 0,
                true, 0, false, false, GUEST_2, EARLIER);

        var scope = OrderSessionScope.of(List.of(noRecord, undecided));

        assertEquals(Set.of(GUEST_1, GUEST_2), scope.protectedGuests());
        assertTrue(scope.cancellationReleases().isEmpty());
    }

    @Test
    void anOrderOfUnknownOwnershipThatIsNotSafelyCancelledStillKeepsTheSessionOpen() {
        var scope = OrderSessionScope.of(List.of(order(null, FulfillmentStatus.RECEIVED, PaymentStatus.NOT_REQUESTED)));

        assertEquals(OrderSessionScope.Knowledge.OPEN_WORK, scope.knowledge());
        assertTrue(scope.protectedGuests().isEmpty());
        assertTrue(scope.protectsMain());
    }

    @Test
    void safelyCancelledNeedsACancelledOrderWhoseMoneyIsClearAndADecisionTime() {
        assertTrue(cancelled(GUEST_1, EARLIER).safelyCancelled());
        assertFalse(order(GUEST_1, FulfillmentStatus.COMPLETED, PaymentStatus.PAID).safelyCancelled());
        assertFalse(new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED, 0,
                false, 0, true, false, GUEST_1, EARLIER).safelyCancelled(), "an unverified capture waits");
    }
}
