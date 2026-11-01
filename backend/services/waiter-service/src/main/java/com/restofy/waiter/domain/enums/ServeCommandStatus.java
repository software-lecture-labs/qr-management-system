package com.restofy.waiter.domain.enums;

import java.util.List;

/**
 * State of a serve command as far as Waiter knows it. Only {@code CONFIRMED} and {@code REJECTED} are answers
 * of Order. {@code PENDING} (sent, no answer yet) and {@code UNKNOWN} (the answer was lost or unusable) mean
 * the command may or may not have been applied: nobody may claim "served" or "not served" for them.
 */
public enum ServeCommandStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    UNKNOWN;

    public static final List<ServeCommandStatus> UNRESOLVED = List.of(PENDING, UNKNOWN);
}
