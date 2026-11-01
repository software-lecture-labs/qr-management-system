package com.restofy.waiter.domain.enums;

/** Kitchen V1 preparation states (order based). READY stays READY after the order is served. */
public enum PreparationStatus {
    WAITING,
    PREPARING,
    READY,
    CANCELLED
}
