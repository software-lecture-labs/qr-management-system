package com.restofy.order.domain.closure;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the Order Service knows about one order of a table session, reduced to what closing and idle expiry need. Money
 * is the Payment Service's verified word as recorded here ({@code payment}, {@code dueMinor}); nothing comes from a
 * client.
 *
 * @param outstandingRefundMinor money that cancellations require to be refunded and the Payment Service has not confirmed
 * @param pendingCapture         a trusted PAID waits for its PENDING (not verified yet)
 * @param reconciliationCase     reported money was recorded that could not be applied to this order
 * @param guestSessionId         the guest session that placed the order (null when not relevant to the rule)
 * @param cancellationSafeAt     when the last applied cancellation of a cancelled order was decided (null otherwise)
 */
public record OrderClosureFacts(UUID orderId, FulfillmentStatus fulfillment, PaymentStatus payment, long dueMinor,
                                boolean openCancellation, long outstandingRefundMinor, boolean pendingCapture,
                                boolean reconciliationCase, UUID guestSessionId, Instant cancellationSafeAt) {

    public OrderClosureFacts {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(fulfillment, "fulfillment");
        Objects.requireNonNull(payment, "payment");
    }

    /** The facts closing needs; the guest and the cancellation time only matter to the idle rules. */
    public OrderClosureFacts(UUID orderId, FulfillmentStatus fulfillment, PaymentStatus payment, long dueMinor,
                             boolean openCancellation, long outstandingRefundMinor, boolean pendingCapture,
                             boolean reconciliationCase) {
        this(orderId, fulfillment, payment, dueMinor, openCancellation, outstandingRefundMinor, pendingCapture,
                reconciliationCase, null, null);
    }

    /**
     * A cancelled order whose money is clear: nothing is owed or uncertain any more, so it no longer protects its guest
     * or the session. A refund still owed, a payment in flight, an unverified or unreconciled report, or a cancellation
     * nobody decided keeps it protecting them.
     */
    public boolean safelyCancelled() {
        return fulfillment == FulfillmentStatus.CANCELLED && cancellationSafeAt != null
                && payment != PaymentStatus.PENDING && outstandingRefundMinor == 0 && !pendingCapture
                && !reconciliationCase && !openCancellation;
    }
}
