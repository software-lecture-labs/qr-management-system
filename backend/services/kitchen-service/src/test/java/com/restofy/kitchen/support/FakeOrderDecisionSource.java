package com.restofy.kitchen.support;

import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.domain.enums.OrderDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * TEST-ONLY stand-in for the Order service's decisions. It builds the proposed decision messages exactly as the contract
 * describes them, so tests can feed them to Kitchen (approved, refused, repeated, old, forged ...). It proves how Kitchen
 * behaves towards that proposed contract; the real Order service sends nothing and no real transport exists (D-10).
 */
public final class FakeOrderDecisionSource {

	private FakeOrderDecisionSource() {
	}

	/** A decision under construction; every field has a valid default, tests change only what they test. */
	public record Draft(UUID eventId, UUID restaurantId, UUID orderId, UUID requestId, long orderVersion, OrderDecision decision,
			Instant decidedAt, String reasonCode, String correlationId, String eventType, int schemaVersion) {

		public Draft eventId(UUID value) {
			return new Draft(value, restaurantId, orderId, requestId, orderVersion, decision, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft restaurant(UUID value) {
			return new Draft(eventId, value, orderId, requestId, orderVersion, decision, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft order(UUID value) {
			return new Draft(eventId, restaurantId, value, requestId, orderVersion, decision, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft request(UUID value) {
			return new Draft(eventId, restaurantId, orderId, value, orderVersion, decision, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft version(long value) {
			return new Draft(eventId, restaurantId, orderId, requestId, value, decision, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft at(Instant value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, decision, value, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public Draft reason(String value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, decision, decidedAt, value, correlationId, eventType,
					schemaVersion);
		}

		public Draft correlation(String value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, decision, decidedAt, reasonCode, value, eventType,
					schemaVersion);
		}

		public Draft type(String value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, decision, decidedAt, reasonCode, correlationId, value,
					schemaVersion);
		}

		public Draft schema(int value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, decision, decidedAt, reasonCode, correlationId, eventType,
					value);
		}

		public Draft decision(OrderDecision value) {
			return new Draft(eventId, restaurantId, orderId, requestId, orderVersion, value, decidedAt, reasonCode, correlationId, eventType,
					schemaVersion);
		}

		public OrderCancellationDecisionMessage message() {
			return new OrderCancellationDecisionMessage(eventId, eventType, schemaVersion, restaurantId, orderId, requestId, orderVersion,
					decision, decidedAt, reasonCode, correlationId);
		}
	}

	/** The Order service approves (cancels) the order; a new event id, the given order version, decided just now. */
	public static Draft approved(UUID restaurantId, UUID orderId, UUID requestId, long orderVersion) {
		return new Draft(UUID.randomUUID(), restaurantId, orderId, requestId, orderVersion, OrderDecision.APPROVED, Instant.now(), null,
				"corr-order-1", OrderCancellationDecisionMessage.TYPE_APPROVED, OrderCancellationDecisionMessage.SCHEMA_VERSION);
	}

	/** The Order service refuses; its own reason code says why. */
	public static Draft rejected(UUID restaurantId, UUID orderId, UUID requestId, long orderVersion) {
		return new Draft(UUID.randomUUID(), restaurantId, orderId, requestId, orderVersion, OrderDecision.REJECTED, Instant.now(),
				"ORDER_ALREADY_SERVED", "corr-order-1", OrderCancellationDecisionMessage.TYPE_REJECTED,
						OrderCancellationDecisionMessage.SCHEMA_VERSION);
	}
}
