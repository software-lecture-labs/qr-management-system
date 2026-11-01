package com.restofy.qr.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Read-only Order authority, not a checkout or expiry permit and never persisted as an order ledger. */
public record OrderSessionState(UUID restaurantId, UUID sessionId, Knowledge knowledge, long revision,
                                Instant verifiedAt, Instant validUntil, Set<UUID> protectedGuests,
                                Map<UUID, Instant> cancellationReleases, Instant mainCancellationAt) {
    public enum Knowledge { UNKNOWN, NO_ORDERS, OPEN_WORK, SETTLED }

    public OrderSessionState {
        Objects.requireNonNull(restaurantId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(knowledge);
        protectedGuests = Set.copyOf(protectedGuests);
        cancellationReleases = Map.copyOf(cancellationReleases);
        if (revision < 0 || (knowledge != Knowledge.UNKNOWN && (revision == 0 || verifiedAt == null
                || validUntil == null || !validUntil.isAfter(verifiedAt)))) {
            throw new IllegalArgumentException("Invalid Order session authority.");
        }
        if (knowledge == Knowledge.NO_ORDERS && !protectedGuests.isEmpty()) {
            throw new IllegalArgumentException("Conflicting Order session authority.");
        }
        if (cancellationReleases.keySet().stream().anyMatch(protectedGuests::contains)) {
            throw new IllegalArgumentException("A protected guest cannot be released.");
        }
    }

    public static OrderSessionState unknown(UUID restaurantId, UUID sessionId) {
        return new OrderSessionState(restaurantId, sessionId, Knowledge.UNKNOWN, 0, null, null, Set.of(), Map.of(), null);
    }

    public boolean usableFor(UUID restaurant, UUID session, Instant now) {
        return restaurantId.equals(restaurant) && sessionId.equals(session) && knowledge != Knowledge.UNKNOWN
                && !verifiedAt.isAfter(now) && validUntil.isAfter(now)
                && cancellationReleases.values().stream().noneMatch(at -> at.isAfter(verifiedAt))
                && (mainCancellationAt == null || !mainCancellationAt.isAfter(verifiedAt));
    }

    public boolean protectsMain() { return !protectedGuests.isEmpty() || knowledge == Knowledge.OPEN_WORK || knowledge == Knowledge.SETTLED; }
    public boolean protectsGuest(UUID guestId) { return protectedGuests.contains(guestId); }
}
