package com.restofy.order.domain.transition;

import com.restofy.order.domain.enums.PaymentStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * The payment state machine, independent of the fulfillment lifecycle. Only the Payment Service reports these
 * statuses (no staff or guest endpoint exists):
 * <pre>
 *   NOT_REQUESTED &gt; PENDING &gt; PAID | FAILED | CANCELLED        FAILED &gt; PENDING (a new attempt)
 * </pre>
 * PAID and CANCELLED are final. The same rules are repeated as a guard trigger in the database (V45).
 */
public final class PaymentTransitions {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            PaymentStatus.NOT_REQUESTED, Set.of(PaymentStatus.PENDING),
            PaymentStatus.PENDING, Set.of(PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.FAILED, Set.of(PaymentStatus.PENDING),
            PaymentStatus.PAID, Set.of(),
            PaymentStatus.CANCELLED, Set.of());

    /** Statuses a payment event may report. NOT_REQUESTED is the initial state and is never reported. */
    public static final Set<PaymentStatus> REPORTABLE = Set.of(PaymentStatus.PENDING, PaymentStatus.PAID,
            PaymentStatus.FAILED, PaymentStatus.CANCELLED);

    /** Statuses whose event must carry (and be checked against) the amount and currency of the order. */
    public static final Set<PaymentStatus> AMOUNT_CHECKED = Set.of(PaymentStatus.PENDING, PaymentStatus.PAID);

    private PaymentTransitions() {
    }

    public static boolean isAllowed(PaymentStatus from, PaymentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Like {@link #isAllowed(PaymentStatus, PaymentStatus)}, plus one controlled move: a FAILED payment may be closed
     * (CANCELLED) once the order itself is cancelled. Nothing was collected in FAILED, and the failed attempt would
     * otherwise stay open forever. A failed payment of a live order stays FAILED: the guest may try again.
     */
    public static boolean isAllowed(PaymentStatus from, PaymentStatus to, boolean orderCancelled) {
        return isAllowed(from, to)
                || (orderCancelled && from == PaymentStatus.FAILED && to == PaymentStatus.CANCELLED);
    }

    public static Set<PaymentStatus> allowedNext(PaymentStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /**
     * True if the event can change nothing because it is late or repeated: same status again, a PENDING that arrives
     * after the payment already ended (PAID/CANCELLED), or a PENDING/FAILED/CANCELLED older than the last applied event.
     *
     * <p>A PAID is never stale because of its timestamp. The order of events is decided by the state machine, not by
     * clocks that may differ between services; a late PAID that the state allows is money that really arrived and must
     * not be dropped just because a newer event was applied first.
     */
    public static boolean isStale(PaymentStatus current, PaymentStatus target, Instant eventAt, Instant lastEventAt) {
        if (target == current) {
            return true;
        }
        if (target == PaymentStatus.PAID) {
            return false;
        }
        if (target == PaymentStatus.PENDING && (current == PaymentStatus.PAID || current == PaymentStatus.CANCELLED)) {
            return true;
        }
        return lastEventAt != null && eventAt.isBefore(lastEventAt);
    }
}
