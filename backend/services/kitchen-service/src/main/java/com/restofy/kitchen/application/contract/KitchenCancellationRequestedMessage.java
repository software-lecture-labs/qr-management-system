package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Proposed, versioned "the kitchen asks for this whole order to be cancelled" event (schemaVersion 1). It is a REQUEST:
 * only the Order service decides, and an acknowledgement of this event is not that decision. Not yet agreed with or
 * implemented by the Order service. Minimal on purpose: the reason CODE only, no free text, user, table or price data.
 */
public record KitchenCancellationRequestedMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		UUID restaurantId,
		UUID orderId,
		UUID requestId,
		long aggregateVersion,
		String correlationId,
		UUID causationId,
		Payload payload) implements OutboundMessage {

	public static final String TYPE = "KitchenCancellationRequested";
	public static final int SCHEMA_VERSION = 1;

	/** {@code productId} / {@code orderItemId} are only set for an OUT_OF_STOCK request (the line that ran out). */
	public record Payload(UUID orderId, String reasonCode, UUID productId, UUID orderItemId) {
	}
}
