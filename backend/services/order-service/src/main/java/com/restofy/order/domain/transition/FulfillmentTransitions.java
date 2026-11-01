package com.restofy.order.domain.transition;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.enums.StaffRole;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The fulfillment state machine and who may drive it. The lifecycle is one strict line
 * RECEIVED, PREPARING, READY, SERVED, COMPLETED; no step may be skipped and nothing moves backwards. CANCELLED is not
 * reachable here at all: it belongs to the cancellation use case (ORDER-04), never to a raw status change.
 *
 * <p>Who sets which status (least privilege):
 * <ul>
 *   <li>PREPARING, READY: the Kitchen (the Kitchen service; KITCHEN staff only where
 *       {@code FulfillmentAuthorityPolicy} allows it, i.e. dev/test).</li>
 *   <li>SERVED: WAITER or ADMIN staff (an ADMIN's action is recorded with its actor like any other).</li>
 *   <li>COMPLETED: WAITER or ADMIN staff. It does not require payment; payment is a separate axis.</li>
 * </ul>
 * Guests can never change a status. The same rules are repeated as a guard trigger in the database (V45).
 */
public final class FulfillmentTransitions {

    private static final Map<FulfillmentStatus, FulfillmentStatus> NEXT = Map.of(
            FulfillmentStatus.RECEIVED, FulfillmentStatus.PREPARING,
            FulfillmentStatus.PREPARING, FulfillmentStatus.READY,
            FulfillmentStatus.READY, FulfillmentStatus.SERVED,
            FulfillmentStatus.SERVED, FulfillmentStatus.COMPLETED);

    private static final Map<StaffRole, Set<FulfillmentStatus>> STAFF_MAY_SET = Map.of(
            StaffRole.KITCHEN, Set.of(FulfillmentStatus.PREPARING, FulfillmentStatus.READY),
            StaffRole.WAITER, Set.of(FulfillmentStatus.SERVED, FulfillmentStatus.COMPLETED),
            StaffRole.ADMIN, Set.of(FulfillmentStatus.SERVED, FulfillmentStatus.COMPLETED));

    private static final Map<ServiceName, Set<FulfillmentStatus>> SERVICE_MAY_SET = Map.of(
            ServiceName.KITCHEN, Set.of(FulfillmentStatus.PREPARING, FulfillmentStatus.READY),
            ServiceName.PAYMENT, Set.of());

    private FulfillmentTransitions() {
    }

    /** The one status an order in {@code from} may move to next, if any. */
    public static Optional<FulfillmentStatus> next(FulfillmentStatus from) {
        return Optional.ofNullable(NEXT.get(from));
    }

    public static boolean isNextStep(FulfillmentStatus from, FulfillmentStatus to) {
        return next(from).filter(to::equals).isPresent();
    }

    /**
     * True if {@code target} is the current status or an earlier point of the line, i.e. a late or repeated message
     * that cannot change anything. CANCELLED is not on the line.
     */
    public static boolean isAtOrBehind(FulfillmentStatus current, FulfillmentStatus target) {
        return current != FulfillmentStatus.CANCELLED && target != FulfillmentStatus.CANCELLED
                && target.ordinal() <= current.ordinal();
    }

    public static boolean mayStaffSet(StaffRole role, FulfillmentStatus target) {
        return STAFF_MAY_SET.getOrDefault(role, Set.of()).contains(target);
    }

    public static boolean mayServiceSet(ServiceName service, FulfillmentStatus target) {
        return SERVICE_MAY_SET.getOrDefault(service, Set.of()).contains(target);
    }
}
