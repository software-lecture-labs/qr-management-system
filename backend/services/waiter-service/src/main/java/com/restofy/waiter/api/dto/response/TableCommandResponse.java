package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The technical command record plus what QR confirmed. {@code COMPLETED} and {@code REJECTED} are final answers of
 * QR. {@code IN_PROGRESS} is QR's answer that the close is under way and is <b>not</b> a success;
 * {@code PENDING} and {@code UNKNOWN} mean the command may or may not have been applied.
 * {@code reconciliation} is {@code NOT_RECORDED} when QR was asked and has no record of the command.
 * {@code closure} (close) and {@code cleaning} (cleaned) are present only for a completed command; they repeat
 * QR's confirmation and never anything Waiter inferred.
 */
public record TableCommandResponse(UUID commandId, TableCommandType type, UUID tableId, UUID tableSessionId,
                                   long expectedVersion, TableCommandStatus status, String rejectionCode,
                                   List<String> blockers, Instant requestedAt, Instant completedAt,
                                   String reconciliation, Closure closure, Cleaning cleaning) {

    /**
     * QR closed the main session at {@code closedAt}; all its guest sessions ended; the unresolved calls ended with it.
     * {@code reconciliationRequired} repeats QR's warning that Order found a scope drift after the closure: the
     * closure stands, the drift is reconciled by QR and Order, and nothing has to be closed or paid again.
     */
    public record Closure(Instant closedAt, int endedGuestSessions, int cancelledCalls, boolean reconciliationRequired) {
    }

    /** QR made the table available to the next customer; {@code tableVersion} is the table's version afterwards. */
    public record Cleaning(long tableVersion, boolean readyForNewCustomer) {
    }
}
