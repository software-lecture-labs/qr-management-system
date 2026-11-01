package com.restofy.order.domain.cancellation;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * Who may cancel an order in which state, and whether the Kitchen has to confirm. Pure rules, no I/O.
 *
 * <pre>
 *   fulfillment   customer                               waiter / admin           Kitchen's own request
 *   RECEIVED      immediately                            immediately
 *   PREPARING     asks, the Kitchen must confirm         asks, the Kitchen must confirm
 *   READY...      refused (D-02: the product decision    refused
 *   COMPLETED     on cancelling a ready order is open)
 *   CANCELLED     refused (already cancelled)            refused
 *
 *   Kitchen's own request (the Kitchen service asks to cancel an order it cannot make): accepted for RECEIVED and
 *   PREPARING and decided at once by the Order Service (the Kitchen's request is its own confirmation); never for a PAID
 *   order, whose cancellation and refund need are for a waiter or admin to decide.
 *
 *   payment PAID  a customer cannot cancel a paid order; a waiter or admin can, and the refund it requires is recorded
 *                 (never performed or assumed here)
 * </pre>
 * The confirmation can only come from the trusted Kitchen service; without it a request stays open, so a cancellation
 * that touches an order already in preparation is never finalised on an unverified word.
 *
 * <p><b>D-02 (decided, closed on purpose):</b> an order that is READY, SERVED or COMPLETED has no cancellation process
 * at all, for any requester and whatever the payment state. A waiter's or admin's authority does not open it, and a
 * refund need is never a way around it: whether and how a prepared or served order is corrected (manager intervention,
 * refund, operational correction) is a separate workflow that does not exist yet. This is the only place that rule is
 * written down; callers never repeat it.
 */
public final class CancellationPolicy {

    public enum Requester { GUEST, WAITER, ADMIN, KITCHEN }

    public enum Mode { IMMEDIATE, NEEDS_KITCHEN_CONFIRMATION }

    /** The fulfillment states from which no cancellation is possible (D-02), besides CANCELLED itself. */
    public static final Set<FulfillmentStatus> CLOSED_FOR_CANCELLATION = Set.of(FulfillmentStatus.READY,
            FulfillmentStatus.SERVED, FulfillmentStatus.COMPLETED);

    private CancellationPolicy() {
    }

    /** @throws BusinessException CANCELLATION_NOT_ALLOWED or ORDER_ALREADY_CANCELLED when the request must be refused */
    public static Mode evaluate(Requester requester, FulfillmentStatus fulfillment, PaymentStatus payment) {
        if (fulfillment == FulfillmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED, "The order is already cancelled");
        }
        if (CLOSED_FOR_CANCELLATION.contains(fulfillment)) {
            throw notAllowed("ORDER_ALREADY_PREPARED", fulfillment, payment,
                    "An order that is ready, served or completed can no longer be cancelled");
        }
        if (requester == Requester.GUEST && payment == PaymentStatus.PAID) {
            throw notAllowed("ORDER_ALREADY_PAID", fulfillment, payment,
                    "A paid order cannot be cancelled by the customer; please ask a waiter");
        }
        if (requester == Requester.KITCHEN) {
            if (payment == PaymentStatus.PAID) {
                throw notAllowed("ORDER_ALREADY_PAID", fulfillment, payment,
                        "A paid order is not cancelled on the kitchen's request; a waiter or admin decides");
            }
            return Mode.IMMEDIATE;   // the Kitchen's own request is its confirmation
        }
        return fulfillment == FulfillmentStatus.RECEIVED ? Mode.IMMEDIATE : Mode.NEEDS_KITCHEN_CONFIRMATION;
    }

    private static BusinessException notAllowed(String reason, FulfillmentStatus fulfillment, PaymentStatus payment,
                                                String message) {
        return new BusinessException(ErrorCode.CANCELLATION_NOT_ALLOWED, message, Map.of("reason", reason,
                "fulfillmentStatus", fulfillment.name(), "paymentStatus", payment.name()));
    }
}
