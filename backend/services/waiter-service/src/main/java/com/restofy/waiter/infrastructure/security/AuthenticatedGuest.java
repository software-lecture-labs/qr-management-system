package com.restofy.waiter.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;
import java.util.UUID;

/**
 * A guest whose credential QR has just verified: the restaurant, table, table session and guest session all
 * come from QR's answer. The credential itself is deliberately not part of this type, so it cannot be stored
 * or logged by accident.
 */
public record AuthenticatedGuest(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId) {

    public AuthenticatedGuest {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(tableSessionId, "tableSessionId");
        Objects.requireNonNull(guestSessionId, "guestSessionId");
    }

    /** Service-boundary check: only a verified guest may raise a call. */
    public static AuthenticatedGuest require(AuthenticatedGuest guest) {
        if (guest == null) throw new AccessDeniedException("A verified guest session is required");
        return guest;
    }
}
