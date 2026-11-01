package com.restofy.order.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The three views of an order list, defined purely by the FULFILLMENT status. Payment is a separate axis: a SERVED or
 * COMPLETED order may be unpaid and a PAID order may still be in preparation, so "completed" never means "paid".
 */
public enum OrderView {
    ACTIVE(EnumSet.of(FulfillmentStatus.RECEIVED, FulfillmentStatus.PREPARING, FulfillmentStatus.READY,
            FulfillmentStatus.SERVED)),
    COMPLETED(EnumSet.of(FulfillmentStatus.COMPLETED)),
    CANCELLED(EnumSet.of(FulfillmentStatus.CANCELLED));

    private final Set<FulfillmentStatus> statuses;

    OrderView(Set<FulfillmentStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<FulfillmentStatus> statuses() {
        return statuses;
    }

    public static Optional<OrderView> parse(String value) {
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException | NullPointerException _) {
            return Optional.empty();
        }
    }
}
