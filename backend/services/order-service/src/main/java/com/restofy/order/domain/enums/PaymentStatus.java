package com.restofy.order.domain.enums;

/** Payment lifecycle as reported by the Payment Service. Not the same thing as cancelling the order. */
public enum PaymentStatus {
    NOT_REQUESTED,
    PENDING,
    PAID,
    FAILED,
    CANCELLED
}
