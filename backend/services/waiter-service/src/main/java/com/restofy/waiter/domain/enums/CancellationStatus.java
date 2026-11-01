package com.restofy.waiter.domain.enums;

/** DRAFT Order contract: cancellation request states (docs/database.md cancellation_requests). */
public enum CancellationStatus {
    REQUESTED,
    APPROVED,
    REJECTED
}
