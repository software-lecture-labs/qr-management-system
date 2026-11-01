package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The domain guards behind an Order decision: what can still be cancelled, and that a decision is written exactly once. */
class CancellationDomainTests {

	private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

	private static KitchenOrder order() {
		return KitchenOrder.receive(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T1", "A-1", T0, 1, UUID.randomUUID(), T0);
	}

	private static CancellationRequest request() {
		return CancellationRequest.open(new CancellationRequest.NewRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				CancellationReason.OTHER, null, null, null, UUID.randomUUID(), T0));
	}

	private static CancellationRequest.Decision decision() {
		return new CancellationRequest.Decision(T0.plusSeconds(30), "ORDER_OK", 2, UUID.randomUUID());
	}

	@Test
	void aWaitingAndAPreparingOrderCanBeCancelledByADecisionAndTakeTheOrderServicesVersion() {
		KitchenOrder waiting = order();
		assertTrue(waiting.canBeCancelledByDecision());
		waiting.cancelByDecision(T0.plusSeconds(30), "OTHER", 5);
		assertEquals(KitchenOrderStatus.CANCELLED, waiting.getStatus());
		assertEquals(T0.plusSeconds(30), waiting.getCancelledAt());
		assertEquals("OTHER", waiting.getCancellationReason());
		assertEquals(5, waiting.getSourceOrderVersion());

		KitchenOrder preparing = order();
		preparing.startPreparation(T0.plusSeconds(10));
		assertTrue(preparing.canBeCancelledByDecision());
		preparing.cancelByDecision(T0.plusSeconds(40), "OTHER", 6);
		assertEquals(KitchenOrderStatus.CANCELLED, preparing.getStatus());
		assertEquals(T0.plusSeconds(10), preparing.getStartedAt(), "the start time stays");
	}

	@Test
	void aReadyOrAlreadyCancelledOrderIsNeverCancelledByADecision() {
		KitchenOrder ready = order();
		ready.startPreparation(T0.plusSeconds(10));
		ready.markReady(T0.plusSeconds(20));
		assertFalse(ready.canBeCancelledByDecision());
		Instant readyCancellationTime = T0.plusSeconds(30);
		assertThrows(InvalidKitchenTransitionException.class,
				() -> ready.cancelByDecision(readyCancellationTime, "OTHER", 5));
		assertEquals(KitchenOrderStatus.READY, ready.getStatus());
		assertNull(ready.getCancelledAt());
		assertEquals(1, ready.getSourceOrderVersion(), "a refused cancellation does not touch the known version");

		KitchenOrder cancelled = order();
		cancelled.cancelByDecision(T0.plusSeconds(30), "OTHER", 5);
		assertFalse(cancelled.canBeCancelledByDecision());
		Instant repeatedCancellationTime = T0.plusSeconds(60);
		assertThrows(InvalidKitchenTransitionException.class,
				() -> cancelled.cancelByDecision(repeatedCancellationTime, "OTHER", 9));
		assertEquals(5, cancelled.getSourceOrderVersion());
	}

	@Test
	void aRequestIsDecidedExactlyOnce() {
		CancellationRequest approved = request();
		approved.approve(decision());
		assertEquals(CancellationRequestStatus.APPROVED, approved.getStatus());
		assertEquals(T0.plusSeconds(30), approved.getDecidedAt());
		assertEquals("ORDER_OK", approved.getDecisionReasonCode());
		assertEquals(2L, approved.getDecisionOrderVersion());
		CancellationRequest.Decision repeatedRejection = decision();
		CancellationRequest.Decision repeatedApproval = decision();
		assertThrows(IllegalStateException.class, () -> approved.reject(repeatedRejection));
		assertThrows(IllegalStateException.class, () -> approved.approve(repeatedApproval));

		CancellationRequest rejected = request();
		rejected.reject(decision());
		assertEquals(CancellationRequestStatus.REJECTED, rejected.getStatus());
		CancellationRequest.Decision approvalAfterRejection = decision();
		assertThrows(IllegalStateException.class, () -> rejected.approve(approvalAfterRejection));
		assertEquals(CancellationRequestStatus.REJECTED, rejected.getStatus());
	}
}
