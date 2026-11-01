package com.restofy.order.domain.event;

/** Services that consume Order Service events. Each gets its own delivery row so retries are tracked per consumer. */
public enum EventDestination {
    KITCHEN,
    QR,
    NOTIFICATION,
    WAITER,
    PAYMENT,
    ADMIN
}
