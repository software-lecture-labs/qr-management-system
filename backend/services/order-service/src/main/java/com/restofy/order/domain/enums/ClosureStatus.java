package com.restofy.order.domain.enums;

/**
 * Where a table-session closure operation stands in the Order Service. Only a closure that was found eligible is ever
 * stored: PREPARED (new orders are refused) then COMPLETED (the QR Service confirmed the table is closed) or RELEASED
 * (the QR Service confirmed it did NOT close, so orders are accepted again). A closure is never released by time.
 */
public enum ClosureStatus {
    PREPARED,
    COMPLETED,
    RELEASED
}
