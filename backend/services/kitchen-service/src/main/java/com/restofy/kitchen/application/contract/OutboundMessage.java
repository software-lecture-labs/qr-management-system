package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * An event Kitchen sends to the Order service through the outbox. Every kind carries the same envelope (stable event
 * id, type, schema version, time, tenant, version, correlation) and the smallest payload it needs. The kinds are
 * proposals: none of them is agreed with the Order service yet.
 */
public sealed interface OutboundMessage permits KitchenOrderReadyMessage, KitchenCancellationRequestedMessage {

	UUID eventId();

	String eventType();

	int schemaVersion();

	Instant occurredAt();

	UUID restaurantId();

	long aggregateVersion();

	String correlationId();

	UUID causationId();
}
