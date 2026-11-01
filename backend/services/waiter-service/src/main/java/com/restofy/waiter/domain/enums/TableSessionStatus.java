package com.restofy.waiter.domain.enums;

/** DRAFT QR contract: states of the live main table session. Closed or expired sessions are never "current". */
public enum TableSessionStatus {
    OPEN,
    CLOSING
}
