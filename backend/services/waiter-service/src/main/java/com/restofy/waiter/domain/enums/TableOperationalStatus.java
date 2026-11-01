package com.restofy.waiter.domain.enums;

/** DRAFT QR contract: operational state owned by the QR service (docs/database.md dining_tables). */
public enum TableOperationalStatus {
    AVAILABLE,
    OCCUPIED,
    CLEANING,
    DISABLED
}
