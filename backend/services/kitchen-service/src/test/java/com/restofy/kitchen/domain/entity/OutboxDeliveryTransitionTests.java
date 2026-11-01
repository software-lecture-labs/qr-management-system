package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.DeliveryOutcome;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure delivery state machine: retry/backoff, limits, finality, lease. No Spring, no database. */
class OutboxDeliveryTransitionTests {

	private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

	private static OutboxDelivery delivery() {
		var aggregate = new OutboxEvent.Aggregate(UUID.randomUUID(), UUID.randomUUID(), 3);
		var eventData = new OutboxEvent.EventData("KitchenOrderReady", 1, null, UUID.randomUUID(), "{}", T0);
		OutboxEvent event = OutboxEvent.create(aggregate, eventData, T0);
		return OutboxDelivery.pending(event, "ORDER", T0);
	}

	@Test
	void aNewDeliveryIsPendingAndDueImmediately() {
		OutboxDelivery delivery = delivery();

		assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
		assertEquals(0, delivery.getAttemptCount());
		assertTrue(delivery.isDue(T0));
		assertFalse(delivery.isDue(T0.minusSeconds(1)));
	}

	@Test
	void acknowledgementMakesItDeliveredAndFinal() {
		OutboxDelivery delivery = delivery();

		delivery.recordAttempt(DeliveryOutcome.DELIVERED, null, T0.plusSeconds(2));

		assertEquals(DeliveryStatus.DELIVERED, delivery.getStatus());
		assertEquals(1, delivery.getAttemptCount());
		assertEquals(T0.plusSeconds(2), delivery.getDeliveredAt());
		assertNull(delivery.getNextAttemptAt());
		assertFalse(delivery.isDue(T0.plusSeconds(999)));
	}

	@Test
	void everyResultAfterTheDeliveryIsFinalIsIgnoredSoDuplicateAcknowledgementsChangeNothing() {
		OutboxDelivery delivered = delivery();
		delivered.recordAttempt(DeliveryOutcome.DELIVERED, null, T0);
		OutboxDelivery failed = delivery();
		failed.recordAttempt(DeliveryOutcome.PERMANENT_FAILURE, "REJECTED", T0);

		for (DeliveryOutcome late : DeliveryOutcome.values()) {
			delivered.recordAttempt(late, "LATE", T0.plusSeconds(60));
			failed.recordAttempt(late, "LATE", T0.plusSeconds(60));
		}

		assertEquals(DeliveryStatus.DELIVERED, delivered.getStatus());
		assertEquals(1, delivered.getAttemptCount());
		assertEquals(T0, delivered.getDeliveredAt());
		assertEquals(DeliveryStatus.FAILED, failed.getStatus());
		assertEquals(1, failed.getAttemptCount());
		assertEquals("REJECTED", failed.getLastErrorCode());
	}

	@Test
	void anUnknownResultIsRetriedWithADoublingDelayCappedAtOneHour() {
		OutboxDelivery delivery = delivery();
		Instant now = T0;
		long[] expectedSeconds = { 5, 10, 20, 40, 80, 160, 320 };

		for (int attempt = 1; attempt <= expectedSeconds.length; attempt++) {
			delivery.recordAttempt(DeliveryOutcome.RETRYABLE_FAILURE, "TIMEOUT", now);
			assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
			assertEquals(attempt, delivery.getAttemptCount());
			assertEquals(now.plusSeconds(expectedSeconds[attempt - 1]), delivery.getNextAttemptAt(), "attempt " + attempt);
			assertEquals("TIMEOUT", delivery.getLastErrorCode());
			now = delivery.getNextAttemptAt();
		}
		assertEquals(Duration.ofHours(1), OutboxDelivery.backoff(30), "the delay never grows past one hour");
		assertEquals(Duration.ofSeconds(5), OutboxDelivery.backoff(1));
	}

	@Test
	void afterTheLastAllowedAttemptItFailsVisiblyInsteadOfRetryingForever() {
		OutboxDelivery delivery = delivery();
		Instant now = T0;

		for (int i = 0; i < OutboxDelivery.MAX_ATTEMPTS; i++) {
			assertEquals(DeliveryStatus.PENDING, delivery.getStatus(), "still pending before attempt " + (i + 1));
			delivery.recordAttempt(DeliveryOutcome.RETRYABLE_FAILURE, "UNAVAILABLE", now);
			now = now.plus(Duration.ofHours(2));
		}

		assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
		assertEquals(OutboxDelivery.MAX_ATTEMPTS, delivery.getAttemptCount());
		assertEquals(OutboxDelivery.MAX_ATTEMPTS_EXCEEDED, delivery.getLastErrorCode());
		assertNull(delivery.getNextAttemptAt());
		assertFalse(delivery.isDue(now.plus(Duration.ofDays(30))));
	}

	@Test
	void aPermanentFailureStopsImmediately() {
		OutboxDelivery delivery = delivery();

		delivery.recordAttempt(DeliveryOutcome.PERMANENT_FAILURE, "UNSUPPORTED_SCHEMA", T0);

		assertEquals(DeliveryStatus.FAILED, delivery.getStatus());
		assertEquals(1, delivery.getAttemptCount());
		assertEquals("UNSUPPORTED_SCHEMA", delivery.getLastErrorCode());
	}

	@Test
	void claimingLeasesTheDeliveryWithoutCountingAnAttemptAndOnlyWhenDue() {
		OutboxDelivery delivery = delivery();

		assertTrue(delivery.claim(T0, Duration.ofSeconds(60)));
		assertEquals(0, delivery.getAttemptCount(), "an attempt is counted when its result is recorded");
		assertEquals(T0.plusSeconds(60), delivery.getNextAttemptAt());
		assertFalse(delivery.claim(T0.plusSeconds(10), Duration.ofSeconds(60)), "a second worker must not take it");
		assertTrue(delivery.claim(T0.plusSeconds(61), Duration.ofSeconds(60)), "after the lease it is due again (crashed worker)");
	}
}
