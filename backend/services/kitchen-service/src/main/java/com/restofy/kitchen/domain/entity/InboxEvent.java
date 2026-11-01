package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.domain.enums.OrderDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** A decision event of the Order service that Kitchen has processed, and what it did with it. Insert-only. */
@Entity
@Table(name = "inbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxEvent implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "source_service", nullable = false, updatable = false, length = 40)
	private String sourceService;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "event_type", nullable = false, updatable = false, length = 60)
	private String eventType;

	@Column(name = "schema_version", nullable = false, updatable = false)
	private int schemaVersion;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "request_id", nullable = false, updatable = false)
	private UUID requestId;

	@Column(name = "order_version", nullable = false, updatable = false)
	private long orderVersion;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private OrderDecision decision;

	@Column(name = "decided_at", nullable = false, updatable = false)
	private Instant decidedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 30)
	private DecisionOutcome outcome;

	@Column(name = "correlation_id", updatable = false, length = 64)
	private String correlationId;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	@Transient
	private boolean isNew = true;

	/** What the Order service's event said, as the service read it. */
	public record Received(UUID eventId, String eventType, int schemaVersion, UUID restaurantId, UUID orderId, UUID requestId,
			long orderVersion, OrderDecision decision, Instant decidedAt, String correlationId) {
	}

	public static InboxEvent received(String sourceService, Received data, DecisionOutcome outcome, Instant receivedAt) {
		InboxEvent event = new InboxEvent();
		event.id = UUID.randomUUID();
		event.sourceService = sourceService;
		event.eventId = data.eventId();
		event.eventType = data.eventType();
		event.schemaVersion = data.schemaVersion();
		event.restaurantId = data.restaurantId();
		event.orderId = data.orderId();
		event.requestId = data.requestId();
		event.orderVersion = data.orderVersion();
		event.decision = data.decision();
		event.decidedAt = data.decidedAt();
		event.outcome = outcome;
		event.correlationId = data.correlationId();
		event.receivedAt = receivedAt;
		return event;
	}

	/** Is this stored event the very event described by the data (same order, request, decision and version)? */
	public boolean isSameEventAs(Received data) {
		return restaurantId.equals(data.restaurantId()) && orderId.equals(data.orderId()) && requestId.equals(data.requestId())
				&& decision == data.decision() && orderVersion == data.orderVersion();
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}
}
