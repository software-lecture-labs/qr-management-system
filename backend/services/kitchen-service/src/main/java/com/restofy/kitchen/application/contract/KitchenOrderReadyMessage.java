package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Proposed, versioned "order is ready in the kitchen" event (schemaVersion 1). Not yet agreed with or implemented
 * by the Order service. Minimal on purpose: no user, table, product, note or price data.
 */
public record KitchenOrderReadyMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		UUID restaurantId,
		UUID orderId,
		long aggregateVersion,
		String correlationId,
		UUID causationId,
		Payload payload) implements OutboundMessage {

	public static final String TYPE = "KitchenOrderReady";
	public static final int SCHEMA_VERSION = 1;

	public record Payload(String preparationStatus, Instant startedAt, Instant readyAt) {
	}
}
