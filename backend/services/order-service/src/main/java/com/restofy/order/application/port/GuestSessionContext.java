package com.restofy.order.application.port;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a QR guest as resolved by the QR Service. Every id here comes from the trusted
 * {@link QrSessionPort}; none of them is ever read from a request body, query or caller-supplied header.
 */
public record GuestSessionContext(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                                  SessionState state) {

    public enum SessionState { ACTIVE, EXPIRED, CLOSED }

    public GuestSessionContext {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(tableSessionId, "tableSessionId");
        Objects.requireNonNull(guestSessionId, "guestSessionId");
        Objects.requireNonNull(state, "state");
    }

    public boolean isActive() {
        return state == SessionState.ACTIVE;
    }
}
