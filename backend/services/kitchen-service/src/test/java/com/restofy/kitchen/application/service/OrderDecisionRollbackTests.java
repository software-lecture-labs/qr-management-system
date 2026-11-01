package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.domain.entity.InboxEvent;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.infrastructure.persistence.repository.InboxEventRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Atomicity: an approved decision changes the order, its history, the request and the inbox together or not at all. */
@SpringBootTest
class OrderDecisionRollbackTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private KitchenStatusHistoryRepository history;
	@MockitoSpyBean
	private InboxEventRepository inbox;

	private record Asked(UUID restaurant, UUID orderId, UUID requestId) {
	}

	private Asked asked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID requestId = cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.OTHER, null)).request().getId();
		return new Asked(restaurant, orderId, requestId);
	}

	private Map<String, Object> state(Asked a) {
		return jdbc.queryForMap("""
				SELECT o.status, o.cancelled_at, o.cancellation_reason, o.source_order_version, o.version AS order_version,
				       r.status AS request_status, r.decided_at, r.version AS request_version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM inbox_events i WHERE i.restaurant_id = o.restaurant_id) AS inbox
				FROM kitchen_orders o JOIN cancellation_requests r ON r.restaurant_id = o.restaurant_id AND r.order_id = o.order_id
				WHERE r.id = ?
				""", a.requestId());
	}

	@Test
	void ifTheHistoryCannotBeWrittenNothingOfTheCancellationRemains() {
		Asked a = asked();
		Map<String, Object> before = state(a);
		doThrow(new DataIntegrityViolationException("simulated history failure")).when(history).saveAndFlush(any(KitchenStatusHistory.class));
		var decision = approved(a.restaurant(), a.orderId(), a.requestId(), 2);
		var message = decision.message();

		assertThrows(DataIntegrityViolationException.class, () -> decisions.apply(SOURCE_ORDER, message));

		assertEquals(before, state(a), "the order is not cancelled, the request not approved, no inbox row");
		assertEquals("WAITING", state(a).get("status"));
	}

	@Test
	void ifTheInboxCannotBeWrittenTheOrderIsNotCancelledEither() {
		Asked a = asked();
		Map<String, Object> before = state(a);
		doThrow(new DataIntegrityViolationException("simulated inbox failure")).when(inbox).saveAndFlush(any(InboxEvent.class));
		var decision = approved(a.restaurant(), a.orderId(), a.requestId(), 2);
		var message = decision.message();

		assertThrows(DataIntegrityViolationException.class, () -> decisions.apply(SOURCE_ORDER, message));

		assertEquals(before, state(a));
	}

	@Test
	void afterAFailedAttemptTheSameEventCanBeAppliedAndTheOutcomeIsTheNormalOne() {
		Asked a = asked();
		var decision = approved(a.restaurant(), a.orderId(), a.requestId(), 2);
		var message = decision.message();
		doThrow(new DataIntegrityViolationException("simulated failure")).when(inbox).saveAndFlush(any(InboxEvent.class));
		assertThrows(DataIntegrityViolationException.class, () -> decisions.apply(SOURCE_ORDER, message));
		Mockito.reset(inbox);

		assertEquals(new OrderDecisionService.DecisionResult(DecisionOutcome.CANCELLED, false),
				decisions.apply(SOURCE_ORDER, decision.message()));

		assertEquals("CANCELLED", state(a).get("status"));
		assertEquals(1, ((Number) state(a).get("inbox")).intValue());
	}
}
