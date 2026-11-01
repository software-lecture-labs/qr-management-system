package com.restofy.waiter.infrastructure.client.qr;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * QR's {@code StaffClosureResponse} (ADR-QR-001 K11): what QR says about a close command. {@code CLOSED} means the
 * main session and every guest session of it have ended, which the answer must prove
 * ({@code guestSessions.ended == total}). {@code CLOSING} means QR accepted the close but has not finished: it is
 * not a success. {@code REJECTED} (only in the status answer) means the close was refused and the session was not
 * closed.
 *
 * <p>A {@code CLOSED} answer may name {@link #RECONCILIATION_REQUIRED} in {@code blockers} (K13): Order found a scope
 * drift after QR had closed locally. The closure stands; it is a warning, never a refusal or a reason to reopen.
 * Any other blocker on a {@code CLOSED} answer is not part of the contract, so the answer is refused as unusable.
 *
 * <p>{@code table} is the table as QR holds it when it answers. Right after the close it is {@code CLEANING}
 * (the command's own answer must show that); a later status answer may find it cleaned or reused, so only the
 * impossible is refused here: a table that still has a live session after its session closed.
 *
 * <p>An answer that contradicts itself is refused as unusable, so Waiter never reports a closure on partial or
 * inconsistent information.
 */
public record QrCloseResultDto(UUID commandId, State state, UUID tableId, UUID tableSessionId, Instant closedAt,
                               GuestSessions guestSessions, QrTableDto table, String rejectionCode,
                               List<String> blockers) {

    /** The only blocker QR names on a {@code CLOSED} answer. */
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";

    public enum State { CLOSED, CLOSING, REJECTED }

    /** Guest sessions bound to the main session: how many exist and how many have ended. */
    public record GuestSessions(int total, int ended) {
        public GuestSessions {
            nonNegative(total, "guestSessions.total");
            nonNegative(ended, "guestSessions.ended");
            if (ended > total) throw new IllegalArgumentException("guestSessions.ended must not exceed total");
        }
    }

    public QrCloseResultDto {
        required(commandId, "commandId");
        required(state, "state");
        required(tableId, "tableId");
        required(tableSessionId, "tableSessionId");
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        switch (state) {
            case CLOSED -> {
                required(closedAt, "closedAt");
                required(guestSessions, "guestSessions");
                if (guestSessions.ended() != guestSessions.total()) {
                    throw new IllegalArgumentException("a closed session must have ended every guest session");
                }
                if (rejectionCode != null) throw new IllegalArgumentException("a closed session has no rejection");
                if (!blockers.stream().allMatch(RECONCILIATION_REQUIRED::equals)) {
                    throw new IllegalArgumentException("a closed session names no blocker but " + RECONCILIATION_REQUIRED);
                }
                if (table != null) {
                    if (!tableId.equals(table.id())) throw new IllegalArgumentException("answer for another table");
                    if (table.currentSession() != null && tableSessionId.equals(table.currentSession().id())) {
                        throw new IllegalArgumentException("a closed session cannot still be the live session of the table");
                    }
                }
            }
            case CLOSING -> {
                if (rejectionCode != null) throw new IllegalArgumentException("a close in progress has no rejection");
            }
            case REJECTED -> requiredText(rejectionCode, "rejectionCode");
        }
    }

    /** A {@code CLOSED} answer that carries QR's K13 reconciliation warning. */
    public boolean reconciliationRequired() {
        return state == State.CLOSED && blockers.contains(RECONCILIATION_REQUIRED);
    }
}
