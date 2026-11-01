package com.restofy.order.domain.enums;

/**
 * Order/service lifecycle, kept separate from {@link PaymentStatus}. Kitchen BEKLIYOR / HAZIRLANIYOR / HAZIR map to
 * RECEIVED / PREPARING / READY.
 */
public enum FulfillmentStatus {
    RECEIVED,
    PREPARING,
    READY,
    SERVED,
    COMPLETED,
    CANCELLED
}
