package com.restofy.order.domain.closure;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-QR-001 K2, K3, K7: the rule that decides whether a table session may be closed, without any database. */
class ClosureEligibilityTest {

    private static OrderClosureFacts order(FulfillmentStatus fulfillment, PaymentStatus payment, long due) {
        return new OrderClosureFacts(UUID.randomUUID(), fulfillment, payment, due, false, 0, false, false);
    }

    private static OrderClosureFacts finishedAndPaid() {
        return order(FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 0);
    }

    private static ClosureEligibility.Result evaluate(OrderClosureFacts... orders) {
        return ClosureEligibility.evaluate(List.of(orders));
    }

    @Test
    void aSessionWithoutAnyOrderIsNeverEligible() {
        var result = evaluate();

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(Set.of(ClosureBlocker.NO_ORDERS), result.blockers());
        assertEquals(0, result.orderCount());
    }

    @Test
    void finishedAndPaidOrdersAreEligible() {
        var result = evaluate(finishedAndPaid(), finishedAndPaid());

        assertEquals(ClosureOutcome.ELIGIBLE, result.outcome());
        assertTrue(result.blockers().isEmpty());
        assertTrue(result.eligible());
        assertEquals(2, result.orderCount());
    }

    @Test
    void paymentAloneNeverClosesATableWhoseFoodIsStillOnItsWay() {
        for (FulfillmentStatus unfinished : List.of(FulfillmentStatus.RECEIVED, FulfillmentStatus.PREPARING,
                FulfillmentStatus.READY, FulfillmentStatus.SERVED)) {
            var result = evaluate(order(unfinished, PaymentStatus.PAID, 0));

            assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome(), unfinished.name());
            assertEquals(Set.of(ClosureBlocker.ORDER_NOT_COMPLETED), result.blockers(), unfinished.name());
        }
    }

    @Test
    void aFinishedOrderThatIsNotPaidKeepsTheTableOpen() {
        for (PaymentStatus unpaid : List.of(PaymentStatus.NOT_REQUESTED, PaymentStatus.FAILED,
                PaymentStatus.CANCELLED)) {
            var result = evaluate(order(FulfillmentStatus.COMPLETED, unpaid, 9000));

            assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome(), unpaid.name());
            assertEquals(Set.of(ClosureBlocker.PAYMENT_NOT_SETTLED), result.blockers(), unpaid.name());
        }
    }

    @Test
    void aPaidOrderThatStillOwesMoneyIsNotSettled() {
        var result = evaluate(order(FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 100));

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(Set.of(ClosureBlocker.PAYMENT_NOT_SETTLED), result.blockers());
    }

    @Test
    void aPaymentInFlightIsUnknownNotEligibleAndNotAJudgement() {
        var result = evaluate(order(FulfillmentStatus.COMPLETED, PaymentStatus.PENDING, 9000));

        assertEquals(ClosureOutcome.UNKNOWN, result.outcome());
        assertEquals(Set.of(ClosureBlocker.PAYMENT_IN_PROGRESS), result.blockers());
        assertTrue(!result.eligible());
    }

    @Test
    void aDefiniteBlockerWinsOverAnUncertainOne() {
        var result = evaluate(order(FulfillmentStatus.PREPARING, PaymentStatus.PENDING, 9000));

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(EnumSet.of(ClosureBlocker.ORDER_NOT_COMPLETED, ClosureBlocker.PAYMENT_IN_PROGRESS),
                result.blockers());
    }

    @Test
    void oneUnfinishedOrderAmongFinishedOnesKeepsTheWholeTableOpen() {
        var result = evaluate(finishedAndPaid(), order(FulfillmentStatus.RECEIVED, PaymentStatus.NOT_REQUESTED, 9000));

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(EnumSet.of(ClosureBlocker.ORDER_NOT_COMPLETED, ClosureBlocker.PAYMENT_NOT_SETTLED),
                result.blockers());
    }

    @Test
    void aCancelledOrderOwesNothingAndNeedsNoPayment() {
        var result = evaluate(finishedAndPaid(), order(FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED, 0),
                order(FulfillmentStatus.CANCELLED, PaymentStatus.NOT_REQUESTED, 0),
                order(FulfillmentStatus.CANCELLED, PaymentStatus.FAILED, 0));

        assertEquals(ClosureOutcome.ELIGIBLE, result.outcome());
    }

    @Test
    void aCancelledOrderWhoseRefundIsNotConfirmedKeepsTheTableOpen() {
        var refundOwed = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.CANCELLED, PaymentStatus.PAID, 0,
                false, 9000, false, false);

        var result = evaluate(finishedAndPaid(), refundOwed);

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertEquals(Set.of(ClosureBlocker.REFUND_PENDING), result.blockers());
    }

    @Test
    void aCancelledOrderWhosePaymentIsStillPendingIsUnknown() {
        var result = evaluate(order(FulfillmentStatus.CANCELLED, PaymentStatus.PENDING, 0));

        assertEquals(ClosureOutcome.UNKNOWN, result.outcome());
        assertEquals(Set.of(ClosureBlocker.PAYMENT_IN_PROGRESS), result.blockers());
    }

    @Test
    void aCancellationNobodyDecidedYetBlocks() {
        var open = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.PREPARING, PaymentStatus.NOT_REQUESTED,
                9000, true, 0, false, false);

        var result = evaluate(open);

        assertEquals(ClosureOutcome.NOT_ELIGIBLE, result.outcome());
        assertTrue(result.blockers().contains(ClosureBlocker.CANCELLATION_PENDING));
    }

    @Test
    void moneyThatIsNotVerifiedOrNotReconciledIsUnknownNeverNothingOwed() {
        var unverified = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 0,
                false, 0, true, false);
        var unreconciled = new OrderClosureFacts(UUID.randomUUID(), FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 0,
                false, 0, false, true);

        var first = evaluate(unverified);
        var second = evaluate(unreconciled);

        assertEquals(ClosureOutcome.UNKNOWN, first.outcome());
        assertEquals(Set.of(ClosureBlocker.PAYMENT_VERIFICATION_PENDING), first.blockers());
        assertEquals(ClosureOutcome.UNKNOWN, second.outcome());
        assertEquals(Set.of(ClosureBlocker.RECONCILIATION_REQUIRED), second.blockers());
    }

    @Test
    void theFingerprintIgnoresTheOrderOfTheOrdersButSeesEveryChange() {
        OrderClosureFacts a = finishedAndPaid();
        OrderClosureFacts b = finishedAndPaid();
        List<OrderClosureFacts> forward = List.of(a, b);
        List<OrderClosureFacts> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);

        String fingerprint = ClosureEligibility.evaluate(forward).fingerprint();

        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertEquals(fingerprint, ClosureEligibility.evaluate(reversed).fingerprint());

        assertNotEquals(fingerprint, ClosureEligibility.evaluate(List.of(a, new OrderClosureFacts(b.orderId(),
                FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 1, false, 0, false, false))).fingerprint(), "due");
        assertNotEquals(fingerprint, ClosureEligibility.evaluate(List.of(a, new OrderClosureFacts(b.orderId(),
                FulfillmentStatus.COMPLETED, PaymentStatus.PAID, 0, true, 0, false, false))).fingerprint(),
                "open cancellation");
        assertNotEquals(fingerprint, ClosureEligibility.evaluate(List.of(a)).fingerprint(), "another order set");
    }
}
