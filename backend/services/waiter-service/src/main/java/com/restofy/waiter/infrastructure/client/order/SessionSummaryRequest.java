package com.restofy.waiter.infrastructure.client.order;

import java.util.List;
import java.util.UUID;

/** DRAFT Order contract v0 request body: batch lookup of active order counts for live table sessions. */
public record SessionSummaryRequest(UUID restaurantId, List<UUID> tableSessionIds) {
}
