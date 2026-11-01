package com.restofy.order.domain.closure;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rule that decides whether a table session may be closed (ADR-QR-001 K2, K3, K7). It looks at ALL orders of the
 * session, whichever guest placed them, and is pure: no clock, no database, no client input.
 *
 * <ul>
 *   <li>Every order that is not cancelled must be COMPLETED and PAID with nothing due (K2: paying alone never closes a
 *       table whose food is still on its way, and a finished order that is not paid never closes it either).</li>
 *   <li>A cancelled order needs no payment, but a refund that was required and not confirmed keeps the table open.</li>
 *   <li>A guest who ordered nothing owes nothing and is not part of the scope (K3). A session without any order is
 *       never "paid": an empty scope is {@link ClosureBlocker#NO_ORDERS}, not eligible.</li>
 *   <li>Anything unknown about the money (payment in flight, unverified PAID, unreconciled reports) is
 *       {@link ClosureOutcome#UNKNOWN}, never eligible (fail closed).</li>
 * </ul>
 */
public final class ClosureEligibility {

    /** The conclusion and the fingerprint of exactly the facts it was drawn from. */
    public record Result(ClosureOutcome outcome, Set<ClosureBlocker> blockers, String fingerprint, int orderCount) {

        public boolean eligible() {
            return outcome == ClosureOutcome.ELIGIBLE;
        }
    }

    private ClosureEligibility() {
    }

    public static Result evaluate(List<OrderClosureFacts> orders) {
        Set<ClosureBlocker> blockers = EnumSet.noneOf(ClosureBlocker.class);
        if (orders.isEmpty()) {
            blockers.add(ClosureBlocker.NO_ORDERS);
        }
        for (OrderClosureFacts order : orders) {
            collect(order, blockers);
        }
        ClosureOutcome outcome;
        if (blockers.stream().anyMatch(ClosureBlocker::isDefinite)) {
            outcome = ClosureOutcome.NOT_ELIGIBLE;
        } else if (!blockers.isEmpty()) {
            outcome = ClosureOutcome.UNKNOWN;
        } else {
            outcome = ClosureOutcome.ELIGIBLE;
        }
        return new Result(outcome, blockers, fingerprint(orders), orders.size());
    }

    private static void collect(OrderClosureFacts order, Set<ClosureBlocker> blockers) {
        if (order.fulfillment() == FulfillmentStatus.CANCELLED) {
            if (order.payment() == PaymentStatus.PENDING) {
                blockers.add(ClosureBlocker.PAYMENT_IN_PROGRESS);
            }
        } else {
            if (order.fulfillment() != FulfillmentStatus.COMPLETED) {
                blockers.add(ClosureBlocker.ORDER_NOT_COMPLETED);
            }
            // PENDING: the attempt's result is not known yet. Anything but PAID with nothing due is not settled.
            if (order.payment() == PaymentStatus.PENDING) {
                blockers.add(ClosureBlocker.PAYMENT_IN_PROGRESS);
            } else if (order.payment() != PaymentStatus.PAID || order.dueMinor() != 0) {
                blockers.add(ClosureBlocker.PAYMENT_NOT_SETTLED);
            }
        }
        if (order.openCancellation()) {
            blockers.add(ClosureBlocker.CANCELLATION_PENDING);
        }
        if (order.outstandingRefundMinor() > 0) {
            blockers.add(ClosureBlocker.REFUND_PENDING);
        }
        if (order.pendingCapture()) {
            blockers.add(ClosureBlocker.PAYMENT_VERIFICATION_PENDING);
        }
        if (order.reconciliationCase()) {
            blockers.add(ClosureBlocker.RECONCILIATION_REQUIRED);
        }
    }

    /**
     * A digest of the facts, independent of their order. A closure that was prepared on one fingerprint is only
     * confirmed while the facts still hash to it: a new payment attempt, a cancellation or any other change in between
     * is never ignored (K9).
     */
    static String fingerprint(List<OrderClosureFacts> orders) {
        String canonical = orders.stream().sorted(Comparator.comparing(OrderClosureFacts::orderId))
                .map(o -> o.orderId() + "|" + o.fulfillment() + "|" + o.payment() + "|" + o.dueMinor() + "|"
                        + o.openCancellation() + "|" + o.outstandingRefundMinor() + "|" + o.pendingCapture() + "|"
                        + o.reconciliationCase())
                .collect(Collectors.joining(";", "v1;", ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
