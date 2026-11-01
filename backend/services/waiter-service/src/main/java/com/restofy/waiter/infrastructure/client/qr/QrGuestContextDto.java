package com.restofy.waiter.infrastructure.client.qr;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT QR contract v0: what QR answers when asked to verify a guest credential. QR is the only authority
 * for the restaurant, table, table session and guest session behind a guest token; Waiter never decodes or
 * stores the token itself.
 */
public record QrGuestContextDto(UUID restaurantId, UUID tableId, TableSession tableSession, GuestSession guestSession) {

    /** Main table session states proposed in docs/database.md section 5. */
    public enum TableSessionState { OPEN, CLOSING, CLOSED, EXPIRED }

    /** Guest session states: a DRAFT proposal, to be aligned with the QR team. */
    public enum GuestSessionState { ACTIVE, ENDED, EXPIRED }

    public record TableSession(UUID id, TableSessionState status) {
        public TableSession {
            required(id, "tableSession.id");
            required(status, "tableSession.status");
        }
    }

    public record GuestSession(UUID id, GuestSessionState status) {
        public GuestSession {
            required(id, "guestSession.id");
            required(status, "guestSession.status");
        }
    }

    public QrGuestContextDto {
        required(restaurantId, "restaurantId");
        required(tableId, "tableId");
        required(tableSession, "tableSession");
        required(guestSession, "guestSession");
    }

    /** Only an open main session with an active guest session may raise a call. */
    public boolean isActive() {
        return tableSession.status() == TableSessionState.OPEN && guestSession.status() == GuestSessionState.ACTIVE;
    }
}
