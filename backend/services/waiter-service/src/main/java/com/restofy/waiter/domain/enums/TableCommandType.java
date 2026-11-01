package com.restofy.waiter.domain.enums;

/** The two commands Waiter forwards to QR, which owns tables and sessions: closing a session and cleaning a table. */
public enum TableCommandType {
    CLOSE,
    CLEAN
}
