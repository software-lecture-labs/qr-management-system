package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.DeliveryOutcome;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Delivery of one outbox event to one destination. At-least-once: an unknown result is retried with the same event
 * id with a bounded, growing delay and a hard attempt limit; after that it is FAILED (visible, never silent) and
 * needs manual reconciliation. DELIVERED and FAILED are final and ignore further results.
 */
@Entity
@Table(name = "outbox_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxDelivery implements Persistable<UUID> {

	public static final int MAX_ATTEMPTS = 8;
	static final Duration BASE_DELAY = Duration.ofSeconds(5);
	static final Duration MAX_DELAY = Duration.ofHours(1);
	/** Doubling stops here so the shift can never overflow, long before MAX_DELAY is the real limit. */
	private static final int MAX_BACKOFF_DOUBLINGS = 20;
	public static final String MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED";

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(nullable = false, updatable = false, length = 40)
	private String destination;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DeliveryStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	@Column(name = "last_error_code", length = 80)
	private String lastErrorCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Version
	private Long version;

	@Transient
	private boolean isNew = true;

	public static OutboxDelivery pending(OutboxEvent event, String destination, Instant now) {
		OutboxDelivery delivery = new OutboxDelivery();
		delivery.id = UUID.randomUUID();
		delivery.restaurantId = event.getRestaurantId();
		delivery.eventId = event.getId();
		delivery.destination = destination;
		delivery.status = DeliveryStatus.PENDING;
		delivery.nextAttemptAt = now;
		delivery.createdAt = now;
		return delivery;
	}

	public boolean isDue(Instant now) {
		return status == DeliveryStatus.PENDING && !nextAttemptAt.isAfter(now);
	}

	/**
	 * Takes the delivery for one attempt: pushes {@code nextAttemptAt} out by the lease so that a parallel worker does
	 * not send the same event at the same time, without counting an attempt yet. The attempt is counted when its result
	 * is recorded. If the worker dies, the delivery simply becomes due again after the lease.
	 */
	public boolean claim(Instant now, Duration lease) {
		if (!isDue(now)) {
			return false;
		}
		nextAttemptAt = now.plus(lease);
		return true;
	}

	/** Applies the result of one attempt. Results for a final delivery are ignored (duplicate acknowledgements). */
	public void recordAttempt(DeliveryOutcome outcome, String errorCode, Instant now) {
		if (status != DeliveryStatus.PENDING) {
			return;
		}
		attemptCount++;
		lastAttemptAt = now;
		switch (outcome) {
			case DELIVERED -> {
				status = DeliveryStatus.DELIVERED;
				deliveredAt = now;
				nextAttemptAt = null;
				lastErrorCode = null;
			}
			case PERMANENT_FAILURE -> fail(errorCode);
			case RETRYABLE_FAILURE -> {
				if (attemptCount >= MAX_ATTEMPTS) {
					fail(MAX_ATTEMPTS_EXCEEDED);
				} else {
					lastErrorCode = errorCode;
					nextAttemptAt = now.plus(backoff(attemptCount));
				}
			}
		}
	}

	private void fail(String errorCode) {
		status = DeliveryStatus.FAILED;
		nextAttemptAt = null;
		lastErrorCode = errorCode;
	}

	/** 5 s, 10 s, 20 s ... doubling, never more than one hour. */
	static Duration backoff(int attempt) {
		long factor = 1L << Math.min(attempt - 1, MAX_BACKOFF_DOUBLINGS);
		Duration delay = BASE_DELAY.multipliedBy(factor);
		return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
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
