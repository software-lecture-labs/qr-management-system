package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a kitchen queue. {@code orderId} is the Order service identifier. {@code receivedAt} is the moment
 * the Kitchen service stored the order (not Order's acceptance time) and drives the queue order.
 * {@code newArrival} marks orders that preparation has not started on (status WAITING); it is a display hint and
 * never changes the FIFO order. {@code cancelledAt} / {@code cancellationReason} are {@code null} unless the Order
 * service approved a cancellation.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KitchenOrderSummaryResponse(
		UUID orderId,
		String orderNumber,
		String tableNumber,
		UUID tableSessionId,
		KitchenOrderStatus status,
		Instant receivedAt,
		boolean newArrival,
		int itemCount,
		Instant cancelledAt,
		String cancellationReason) {
}
