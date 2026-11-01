package com.restofy.waiter.domain.enums;

import java.util.List;

/**
 * State of a table command as far as Waiter knows it. Only {@code COMPLETED} and {@code REJECTED} are final
 * answers of QR. {@code PENDING} (sent, no answer yet) and {@code UNKNOWN} (the answer was lost or unusable)
 * mean the command may or may not have been applied. {@code IN_PROGRESS} is an answer of QR that is not a
 * success: QR accepted the close but has not yet ended the main session and all guest sessions.
 */
public enum TableCommandStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    UNKNOWN;

    /** No final answer of QR yet: these commands are reconciled with QR and block a second command for the target. */
    public static final List<TableCommandStatus> OPEN = List.of(PENDING, IN_PROGRESS, UNKNOWN);

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED;
    }
}
