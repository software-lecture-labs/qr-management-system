package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure domain rules of PREPARING -> READY and of READY being terminal (no Spring, no database). */
class KitchenOrderReadyTransitionTests {

	private static final Instant RECEIVED = Instant.parse("2026-10-17T10:00:00Z");
	private static final Instant STARTED = RECEIVED.plusSeconds(60);

	private static KitchenOrder waiting() {
		return KitchenOrder.receive(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1", "A-1", RECEIVED, 1,
				UUID.randomUUID(), RECEIVED);
	}

	private static KitchenOrder preparing() {
		KitchenOrder order = waiting();
		order.startPreparation(STARTED);
		return order;
	}

	private static KitchenOrder inStatus(KitchenOrderStatus status) {
		KitchenOrder order = waiting();
		ReflectionTestUtils.setField(order, "status", status);
		return order;
	}

	@Test
	void preparingOrderBecomesReadyOnceKeepingStartedAt() {
		KitchenOrder order = preparing();
		Instant now = STARTED.plusSeconds(300);

		Instant written = order.markReady(now);

		assertEquals(KitchenOrderStatus.READY, order.getStatus());
		assertEquals(STARTED, order.getStartedAt(), "startedAt must not change");
		assertEquals(now, order.getReadyAt());
		assertEquals(now, written);
	}

	@Test
	void aWaitingOrderCanNeverJumpToReady() {
		KitchenOrder order = waiting();
		Instant attemptedAt = STARTED.plusSeconds(10);

		InvalidKitchenTransitionException e = assertThrows(InvalidKitchenTransitionException.class,
				() -> order.markReady(attemptedAt));

		assertEquals(KitchenOrderStatus.WAITING, e.getCurrentStatus());
		assertEquals(KitchenOrderStatus.READY, e.getTargetStatus());
		assertEquals(KitchenOrderStatus.WAITING, order.getStatus());
		assertNull(order.getStartedAt());
		assertNull(order.getReadyAt());
	}

	@Test
	void readyAndCancelledOrdersCannotBeMarkedReadyAgainAndStayUntouched() {
		for (KitchenOrderStatus status : new KitchenOrderStatus[] { KitchenOrderStatus.READY, KitchenOrderStatus.CANCELLED }) {
			KitchenOrder order = inStatus(status);
			Instant attemptedAt = STARTED.plusSeconds(10);

			assertThrows(InvalidKitchenTransitionException.class, () -> order.markReady(attemptedAt));

			assertEquals(status, order.getStatus());
			assertNull(order.getReadyAt());
		}
	}

	@Test
	void readyAtIsNeverEarlierThanStartedAtEvenWithClockSkew() {
		KitchenOrder order = preparing();

		order.markReady(STARTED.minusSeconds(45));

		assertEquals(STARTED, order.getReadyAt());
		assertTrue(!order.getReadyAt().isBefore(order.getStartedAt()));
		assertTrue(!order.getStartedAt().isBefore(order.getReceivedAt()));
	}

	@Test
	void timesAreStoredWithMicrosecondPrecisionSoAResponseEqualsALaterRead() {
		KitchenOrder order = waiting();

		Instant started = order.startPreparation(STARTED.plusNanos(123_456_789));
		Instant ready = order.markReady(STARTED.plusSeconds(5).plusNanos(987_654_321));

		assertEquals(STARTED.plusNanos(123_456_000), started);
		assertEquals(STARTED.plusSeconds(5).plusNanos(987_654_000), ready);
		assertEquals(started, order.getStartedAt());
		assertEquals(ready, order.getReadyAt());
	}

	@Test
	void readyIsTerminalNoCommandMovesItBackOrChangesItsTimes() {
		KitchenOrder order = preparing();
		Instant ready = order.markReady(STARTED.plusSeconds(120));
		Instant attemptedAt = ready.plusSeconds(5);

		assertThrows(InvalidKitchenTransitionException.class, () -> order.startPreparation(attemptedAt));
		assertThrows(InvalidKitchenTransitionException.class, () -> order.markReady(attemptedAt));

		assertEquals(KitchenOrderStatus.READY, order.getStatus());
		assertEquals(STARTED, order.getStartedAt());
		assertEquals(ready, order.getReadyAt());
	}
}
