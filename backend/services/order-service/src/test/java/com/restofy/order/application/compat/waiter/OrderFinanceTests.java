package com.restofy.order.application.compat.waiter;

import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.entity.OrderItem;
import com.restofy.order.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.order.application.compat.waiter.CompatFixtures.NOW;
import static com.restofy.order.application.compat.waiter.CompatFixtures.item;
import static com.restofy.order.application.compat.waiter.CompatFixtures.order;
import static com.restofy.order.application.compat.waiter.CompatFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The six money concepts of one order stay apart, and contradicting records are never reconciled by guessing. */
class OrderFinanceTests {

    @Test
    void aFreshOrderHasItsOriginalTotalAndNothingCancelledCollectedOrRefunded() {
        Order order = order("TRY", new long[]{9_000, 3}, new long[]{25_000, 1});

        var finance = OrderFinance.compute(order, List.of(), 0);

        assertEquals(52_000, finance.originalGrossMinor());
        assertEquals(0, finance.cancelledGrossMinor());
        assertEquals(52_000, finance.netAfterCancellationsMinor());
        assertEquals(0, finance.collectedMinor());
        assertEquals(52_000, finance.outstandingDueMinor());
        assertEquals(0, finance.refundRequiredMinor());
        assertEquals(0, finance.refundedMinor());
    }

    @Test
    void onlyAppliedCancellationsCountAndTheyAreCountedOnce() {
        Order order = order("TRY", new long[]{9_000, 3}, new long[]{25_000, 1});
        OrderItem salad = item(order, 0);
        salad.applyCancellation(2);
        order.applyCancellation(34_000, false, NOW);
        var applied = row(order, "APPLIED", "STAFF", "u", "WAITER", 18_000, 0, 0);
        var waiting = row(order, "REQUESTED", "GUEST", "g", null, 9_000, 0, 0);
        var rejected = row(order, "REJECTED", "GUEST", "g", null, 0, 0, 0);

        var finance = OrderFinance.compute(order, List.of(applied, waiting, rejected), 0);

        assertEquals(18_000, finance.cancelledGrossMinor(), "a waiting or rejected request cancels nothing");
        assertEquals(34_000, finance.netAfterCancellationsMinor());
        assertEquals(34_000, finance.outstandingDueMinor());
    }

    @Test
    void whatTheOrderIsWorthAndWhatIsStillOwedAreDifferentForAPaidOrder() {
        Order order = order("TRY", new long[]{9_000, 3}, new long[]{25_000, 1});
        // paid in full: the debt is settled (0) but the order is still worth its total
        order.changePaymentStatus(PaymentStatus.PENDING, NOW, NOW);
        order.changePaymentStatus(PaymentStatus.PAID, NOW, NOW);
        item(order, 1).applyCancellation(1);
        order.applyCancellation(0, false, NOW);
        var applied = row(order, "APPLIED", "STAFF", "u", "WAITER", 25_000, 25_000, 0);

        var finance = OrderFinance.compute(order, List.of(applied), 52_000);

        assertEquals(27_000, finance.netAfterCancellationsMinor(), "worth after the cancellation");
        assertEquals(0, finance.outstandingDueMinor(), "nothing left to pay");
        assertEquals(52_000, finance.collectedMinor());
        assertEquals(25_000, finance.refundRequiredMinor(), "required, not made");
        assertEquals(0, finance.refundedMinor());
    }

    @Test
    void aRefundThatHappenedIsReportedAsItIsAndNeverInventedFromTheRequirement() {
        Order order = order("TRY", new long[]{9_000, 1});
        order.getItems().forEach(item -> item.applyCancellation(1));
        order.applyCancellation(0, true, NOW);
        var applied = row(order, "APPLIED", "STAFF", "u", "WAITER", 9_000, 9_000, 4_000);

        var finance = OrderFinance.compute(order, List.of(applied), 9_000);

        assertEquals(9_000, finance.refundRequiredMinor());
        assertEquals(4_000, finance.refundedMinor());
    }

    @Test
    void contradictingRecordsFailInsteadOfBeingGuessed() {
        Order order = order("TRY", new long[]{9_000, 3});
        item(order, 0).applyCancellation(1);
        // the line says 1 unit is cancelled, the records say nothing was applied
        assertThrows(IllegalStateException.class, () -> OrderFinance.compute(order, List.of(), 0));
        // the records say more than the lines
        var tooMuch = row(order, "APPLIED", "STAFF", "u", "WAITER", 27_000, 0, 0);
        var cancellations = List.of(tooMuch);
        assertThrows(IllegalStateException.class, () -> OrderFinance.compute(order, cancellations, 0));
    }

    @Test
    void lineTotalsThatDoNotAddUpToTheOriginalTotalAreRefused() {
        Order inconsistent = new Order(UUID.randomUUID(), "ORD-1", CompatFixtures.RESTAURANT,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "TRY", 10_000, 0, 10_000, null, Instant.now());
        inconsistent.addItem(new OrderItem(inconsistent, 1, UUID.randomUUID(), "x", 1, "TRY", 9_000, 0, 9_000,
                9_000, 0, null, null, Instant.now()));
        assertThrows(IllegalStateException.class, () -> OrderFinance.compute(inconsistent, List.of(), 0));
    }
}
