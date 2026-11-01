package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order's status timeline, oldest first. {@code actorId} is only filled for ADMIN viewers: customers and other staff
 * see what kind of actor acted (and its role), never another person's identifier.
 */
public record OrderHistoryResponse(UUID orderId, List<Entry> entries) {

    public OrderHistoryResponse {
        entries = ResponseCollections.copyOf(entries);
    }

    public record Entry(String axis, String fromStatus, String toStatus, String actorType, String actorRole,
                        String actorId, String source, String reason, Instant occurredAt) {
    }
}
