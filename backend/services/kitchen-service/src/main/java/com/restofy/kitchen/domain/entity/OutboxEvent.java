package com.restofy.kitchen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** An immutable business event recorded in the same transaction as the change that caused it. */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent implements Persistable<UUID> {

	public static final String AGGREGATE_KITCHEN_ORDER = "KITCHEN_ORDER";
	/** One cancellation request of an order; a rejected request can be followed by a new one, each with its own events. */
	public static final String AGGREGATE_CANCELLATION_REQUEST = "CANCELLATION_REQUEST";

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "aggregate_type", nullable = false, updatable = false, length = 40)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	@Column(name = "aggregate_version", nullable = false, updatable = false)
	private long aggregateVersion;

	@Column(name = "event_type", nullable = false, updatable = false, length = 60)
	private String eventType;

	@Column(name = "schema_version", nullable = false, updatable = false)
	private int schemaVersion;

	@Column(name = "correlation_id", updatable = false, length = 64)
	private String correlationId;

	@Column(name = "causation_id", updatable = false)
	private UUID causationId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false, columnDefinition = "jsonb")
	private String payload;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	/** The thing an event is about. Without a type it is the kitchen order; the event kind is unique per aggregate. */
	public record Aggregate(String type, UUID restaurantId, UUID id, long version) {

		public Aggregate(UUID restaurantId, UUID id, long version) {
			this(AGGREGATE_KITCHEN_ORDER, restaurantId, id, version);
		}
	}

	public record EventData(String type, int schemaVersion, String correlationId, UUID causationId, String payload,
			Instant occurredAt) {
	}

	public static OutboxEvent create(Aggregate aggregate, EventData data, Instant now) {
		OutboxEvent event = new OutboxEvent();
		event.id = UUID.randomUUID();
		event.restaurantId = aggregate.restaurantId();
		event.aggregateType = aggregate.type();
		event.aggregateId = aggregate.id();
		event.aggregateVersion = aggregate.version();
		event.eventType = data.type();
		event.schemaVersion = data.schemaVersion();
		event.correlationId = data.correlationId();
		event.causationId = data.causationId();
		event.payload = data.payload();
		event.occurredAt = data.occurredAt();
		event.createdAt = now;
		return event;
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
