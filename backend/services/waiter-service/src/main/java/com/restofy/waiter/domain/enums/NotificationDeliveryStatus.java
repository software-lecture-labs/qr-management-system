package com.restofy.waiter.domain.enums;

/**
 * Delivery state of one call event towards Notification. It is independent of the call's own status:
 * a RESOLVED call stays resolved whatever happens here.
 */
public enum NotificationDeliveryStatus {
    /** Waiting for the first attempt or for a retry after a failure. */
    PENDING,
    /** Notification acknowledged the event (applied, duplicate or already superseded on its side). */
    DELIVERED,
    /** A newer event of the same call exists, so this older state is never sent. */
    SUPERSEDED,
    /** Attempts are exhausted; the failure stays visible until an operator re-queues it. */
    DEAD
}
