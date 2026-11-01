package com.restofy.order.api.dto.response;

import com.restofy.order.domain.closure.OrderSessionScope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the orders of a table session mean for its lifecycle, read-only: not a permit to do anything. The QR Service
 * uses it for its idle rules (ADR-QR-001 K6): {@code protectedGuests} still have an order that is not safely cancelled,
 * {@code cancellationReleases} says since when a guest whose orders were all safely cancelled is released, and
 * {@code mainCancellationAt} since when the main session is (only when every order of the session was safely
 * cancelled). {@code knowledge} is {@code NO_ORDERS}, {@code OPEN_WORK} or {@code SETTLED}; the Order Service never
 * claims "no orders" from missing data. The answer is usable until {@code validUntil}; {@code revision} only goes up.
 */
public record OrderSessionStateResponse(UUID restaurantId, UUID tableSessionId, OrderSessionScope.Knowledge knowledge,
                                        long revision, Instant verifiedAt, Instant validUntil,
                                        List<UUID> protectedGuests, Map<UUID, Instant> cancellationReleases,
                                        Instant mainCancellationAt) {

    public OrderSessionStateResponse {
        protectedGuests = ResponseCollections.copyOf(protectedGuests);
        cancellationReleases = ResponseCollections.copyOf(cancellationReleases);
    }
}
