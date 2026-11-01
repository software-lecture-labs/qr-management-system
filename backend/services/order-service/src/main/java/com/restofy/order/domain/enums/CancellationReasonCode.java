package com.restofy.order.domain.enums;

import java.util.Optional;

/**
 * The one central catalog of cancellation reasons (mirrored by the CHECK constraint of
 * {@code order_cancellation_reasons}, and a test keeps the two equal). Every reason is optional for the customer.
 *
 * <p>{@link #kitchenGroup()} maps each code to the Kitchen V1 reason groups (OUT_OF_STOCK, CUSTOMER_REQUEST,
 * KITCHEN_ERROR, OTHER) so a kitchen-side or admin report can group them without a second catalog.
 */
public enum CancellationReasonCode {
    CHANGED_MIND,
    ORDERED_BY_MISTAKE,
    WAIT_TOO_LONG,
    ITEM_UNAVAILABLE,
    KITCHEN_ERROR,
    OTHER;

    public String kitchenGroup() {
        return switch (this) {
            case CHANGED_MIND, ORDERED_BY_MISTAKE, WAIT_TOO_LONG -> "CUSTOMER_REQUEST";
            case ITEM_UNAVAILABLE -> "OUT_OF_STOCK";
            default -> name();
        };
    }

    /** Exact, case-sensitive match: a code is an identifier, not free text. */
    public static Optional<CancellationReasonCode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
