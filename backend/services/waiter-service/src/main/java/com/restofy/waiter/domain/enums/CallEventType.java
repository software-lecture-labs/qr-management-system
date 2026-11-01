package com.restofy.waiter.domain.enums;

/** Business change of a call that is audited and synchronized to Notification. */
public enum CallEventType {
    CREATED,
    CLAIMED,
    RESOLVED,
    /** The call ended unresolved because its table session was closed (WAITER-04). */
    CANCELLED;

    /** Event name used on the wire to Notification. */
    public String wireName() {
        return "SERVICE_CALL_" + name();
    }
}
