package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure domain rules of the start-preparation transition (no Spring, no database). */
class KitchenOrderTransitionTests {

	private static final Instant RECEIVED = Instant.parse("2026-10-17T10:00:00Z");

	private static KitchenOrder order(KitchenOrderStatus status) {
		KitchenOrder order = KitchenOrder.receive(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1", "A-1",
				RECEIVED, 1, UUID.randomUUID(), RECEIVED);
		ReflectionTestUtils.setField(order, "status", status);
		return order;
	}

	@Test
	void waitingOrderStartsOnceAndKeepsReadyAtNull() {
		KitchenOrder order = order(KitchenOrderStatus.WAITING);
		Instant now = RECEIVED.plusSeconds(90);

		order.startPreparation(now);

		assertEquals(KitchenOrderStatus.PREPARING, order.getStatus());
		assertEquals(now, order.getStartedAt());
		assertNull(order.getReadyAt());
	}

	@Test
	void everyStatusExceptWaitingIsRejectedAndLeftUntouchedIncludingPreparing() {
		for (KitchenOrderStatus status : new KitchenOrderStatus[] { KitchenOrderStatus.PREPARING, KitchenOrderStatus.READY,
				KitchenOrderStatus.CANCELLED }) {
			KitchenOrder order = order(status);
			Instant startedAt = RECEIVED.plusSeconds(10);

			InvalidKitchenTransitionException e = assertThrows(InvalidKitchenTransitionException.class,
					() -> order.startPreparation(startedAt));

			assertEquals(status, e.getCurrentStatus());
			assertEquals(KitchenOrderStatus.PREPARING, e.getTargetStatus());
			assertEquals(status, order.getStatus());
			assertNull(order.getStartedAt());
		}
	}

	@Test
	void messageDistinguishesAnAlreadyReachedStatusFromAnIllegalMove() {
		assertTrue(new InvalidKitchenTransitionException(KitchenOrderStatus.PREPARING, KitchenOrderStatus.PREPARING)
				.getMessage().contains("already PREPARING"));
		String illegal = new InvalidKitchenTransitionException(KitchenOrderStatus.READY, KitchenOrderStatus.PREPARING)
				.getMessage();
		assertTrue(illegal.contains("cannot move to PREPARING"));
		assertFalse(illegal.contains("already"));
	}

	@Test
	void startedAtIsNeverEarlierThanReceivedAtEvenWithClockSkew() {
		KitchenOrder order = order(KitchenOrderStatus.WAITING);

		order.startPreparation(RECEIVED.minusSeconds(30));

		assertEquals(RECEIVED, order.getStartedAt());
	}
}
