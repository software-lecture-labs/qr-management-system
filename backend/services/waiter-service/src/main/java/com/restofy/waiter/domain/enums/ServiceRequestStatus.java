package com.restofy.waiter.domain.enums;

import java.util.List;

/**
 * Call lifecycle proposed in docs/database.md section 9. A call is "open" until it is resolved or
 * cancelled; being read (a Notification concern) never changes this status.
 */
public enum ServiceRequestStatus {
    OPEN,
    CLAIMED,
    RESOLVED,
    CANCELLED;

    public static final List<ServiceRequestStatus> UNRESOLVED = List.of(OPEN, CLAIMED);
}
