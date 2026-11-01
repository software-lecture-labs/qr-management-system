package com.restofy.waiter.infrastructure.client.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.waiter.domain.enums.OrderView;

import java.util.List;
import java.util.UUID;

/**
 * DRAFT Order contract v0 request body of the read-only search. A missing {@code tableSessionIds} means
 * every session; an empty list is invalid and never sent. {@code restaurantId} always comes from the
 * verified staff context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderSearchRequest(UUID restaurantId, UUID tableId, List<UUID> tableSessionIds, OrderView view,
                                 int page, int size) {
}
