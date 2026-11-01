package com.restofy.waiter.infrastructure.client.order;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/** DRAFT Order contract v0: the part of an order line covered by a cancellation request. */
public record CancellationItemDto(UUID orderItemId, int quantity) {

    public CancellationItemDto {
        required(orderItemId, "cancellation.item.orderItemId");
        if (quantity <= 0) throw new IllegalArgumentException("cancellation.item.quantity must be positive");
    }
}
