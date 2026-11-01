package com.restofy.order.domain.enums;

/**
 * Asking and finalising are different facts. A cancellation is REQUESTED first; it is APPLIED (the order really changed)
 * or REJECTED (nothing changed) afterwards, exactly once.
 */
public enum CancellationStatus {
    REQUESTED,
    APPLIED,
    REJECTED
}
