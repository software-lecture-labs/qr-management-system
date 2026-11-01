package com.restofy.waiter.domain.enums;

/** DRAFT Order contract: business axis of an order (docs/architecture.md section 6). */
public enum OrderBusinessStatus {
    ACCEPTED,
    PARTIALLY_CANCELLED,
    CANCELLED,
    COMPLETED
}
