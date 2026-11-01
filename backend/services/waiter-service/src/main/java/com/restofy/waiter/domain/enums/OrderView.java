package com.restofy.waiter.domain.enums;

/**
 * Allow-listed order views of the Waiter API. The predicates are the contract: Order applies them to the
 * whole data set, and Waiter re-checks every returned row so a wrong downstream filter is never shown.
 */
public enum OrderView {
    /** Business status ACCEPTED or PARTIALLY_CANCELLED (not cancelled, not completed). */
    ACTIVE,
    /** Kitchen READY and not cancelled, whether or not it has been served yet: the ready history. */
    READY_HISTORY,
    /** Kitchen READY, not cancelled and delivery still PENDING: the queue waiting for service. */
    AWAITING_DELIVERY,
    /** Business status CANCELLED. Partially cancelled orders stay in ACTIVE and show their cancellations. */
    CANCELLED;

    public boolean matches(OrderBusinessStatus business, PreparationStatus preparation, DeliveryStatus delivery) {
        boolean ready = preparation == PreparationStatus.READY && business != OrderBusinessStatus.CANCELLED;
        return switch (this) {
            case ACTIVE -> business == OrderBusinessStatus.ACCEPTED || business == OrderBusinessStatus.PARTIALLY_CANCELLED;
            case READY_HISTORY -> ready;
            case AWAITING_DELIVERY -> ready && delivery == DeliveryStatus.PENDING;
            case CANCELLED -> business == OrderBusinessStatus.CANCELLED;
        };
    }
}
