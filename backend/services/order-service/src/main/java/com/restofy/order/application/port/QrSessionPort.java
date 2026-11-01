package com.restofy.order.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Contract with the QR Service (session owner). The Order Service never creates or closes sessions.
 *
 * <p>Implementations must be fail-closed: an unknown, revoked or tampered token yields
 * {@link Optional#empty()}; an unreachable QR Service throws a {@code BusinessException} with
 * {@code DEPENDENCY_UNAVAILABLE} instead of ever reporting a session as valid.
 */
public interface QrSessionPort {

    /**
     * Resolves an opaque guest session credential to the main table session + guest session it belongs to,
     * including whether that session is still ACTIVE (not EXPIRED/CLOSED).
     */
    Optional<GuestSessionContext> validateGuestSession(String guestSessionToken);

    /**
     * Current state of the given main table session + guest session, asked again at the moment an order is
     * placed. Empty means the QR Service does not know that (table session, guest session) pair, which must be
     * treated as not active.
     */
    Optional<GuestSessionContext.SessionState> currentSessionState(UUID restaurantId, UUID tableSessionId,
                                                                   UUID guestSessionId);
}
