package com.restofy.waiter.domain.enums;

/** DRAFT Order contract: who asked for the cancellation. The decision always belongs to Order. */
public enum CancellationSource {
    CUSTOMER,
    KITCHEN,
    WAITER
}
