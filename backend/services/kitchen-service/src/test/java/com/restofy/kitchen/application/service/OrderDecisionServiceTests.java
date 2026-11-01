package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.application.service.OrderDecisionService.DecisionResult;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.support.FakeOrderDecisionSource.Draft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.approved;
import static com.restofy.kitchen.support.FakeOrderDecisionSource.rejected;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KITCHEN-04 / 4.3: what Kitchen does with the (fake) Order service's decisions on a real PostgreSQL. Only an APPROVED
 * decision cancels an order, and only when the order can still be cancelled; everything else leaves the order exactly as it
 * was. This proves Kitchen's behaviour towards the PROPOSED contract, not an integration with a real Order service.
 */
@SpringBootTest
class OrderDecisionServiceTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private JdbcTemplate jdbc;

	/** An order with one undecided cancellation request (reason OTHER, an order version of 1). */
	private record Asked(UUID restaurant, UUID orderId, UUID requestId, UUID actor) {
	}

	private Asked asked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID requestId = cancellations.request(restaurant, actor, UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.KITCHEN_ERROR, "yanlış sipariş")).request().getId();
		return new Asked(restaurant, orderId, requestId, actor);
	}

	private Asked preparingAsked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		UUID requestId = cancellations.request(restaurant, actor, UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.CUSTOMER_REQUEST, null)).request().getId();
		return new Asked(restaurant, orderId, requestId, actor);
	}

	private Draft approve(Asked a, long version) {
		return approved(a.restaurant(), a.orderId(), a.requestId(), version);
	}

	private Draft reject(Asked a, long version) {
		return rejected(a.restaurant(), a.orderId(), a.requestId(), version);
	}

	private DecisionResult apply(Draft draft) {
		return decisions.apply(SOURCE_ORDER, draft.message());
	}

	private Map<String, Object> order(Asked a) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.cancelled_at, o.cancellation_reason, o.source_order_version, o.version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e
				        WHERE e.restaurant_id = o.restaurant_id AND e.event_type = 'KitchenOrderReady') AS ready_events
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", a.restaurant(), a.orderId());
	}

	private Map<String, Object> request(Asked a) {
		return jdbc.queryForMap("SELECT * FROM cancellation_requests WHERE id = ?", a.requestId());
	}

	private int inbox(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	// ---- only an approval cancels, atomically ----------------------------------------------------------------------

	@Test
	void anApprovalCancelsAWaitingOrderAndEverythingChangesTogether() {
		Asked a = asked();
		Draft decision = approve(a, 2).at(Instant.now().truncatedTo(ChronoUnit.MICROS));
		Map<String, Object> before = order(a);

		DecisionResult result = apply(decision);

		assertEquals(new DecisionResult(DecisionOutcome.CANCELLED, false), result);
		Map<String, Object> after = order(a);
		assertEquals("CANCELLED", after.get("status"));
		assertEquals(Timestamp.from(decision.decidedAt()), after.get("cancelled_at"), "the Order service's decision time");
		assertEquals("KITCHEN_ERROR", after.get("cancellation_reason"));
		assertEquals(2L, ((Number) after.get("source_order_version")).longValue());
		assertEquals(((Number) before.get("history")).longValue() + 1, ((Number) after.get("history")).longValue());
		Map<String, Object> entry = jdbc.queryForMap("""
				SELECT h.from_status, h.to_status, h.actor_user_id, h.source_event_id, h.command_id, h.reason
				FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'CANCELLED'
				""", a.restaurant(), a.orderId());
		assertEquals("WAITING", entry.get("from_status"));
		assertNull(entry.get("actor_user_id"));
		assertNull(entry.get("command_id"));
		assertEquals(decision.eventId(), entry.get("source_event_id"));
		assertEquals("KITCHEN_ERROR", entry.get("reason"));
		Map<String, Object> req = request(a);
		assertEquals("APPROVED", req.get("status"));
		assertEquals(Timestamp.from(decision.decidedAt()), req.get("decided_at"));
		assertEquals(2L, ((Number) req.get("decision_order_version")).longValue());
		assertEquals(decision.eventId(), req.get("decision_event_id"));
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT outcome FROM inbox_events WHERE restaurant_id = ?", String.class, a.restaurant()));
	}

	@Test
	void anApprovalCancelsAPreparingOrderAndKeepsItsStartTime() {
		Asked a = preparingAsked();
		Object startedAt = order(a).get("started_at");

		assertEquals(DecisionOutcome.CANCELLED, apply(approve(a, 3)).outcome());

		Map<String, Object> after = order(a);
		assertEquals("CANCELLED", after.get("status"));
		assertEquals(startedAt, after.get("started_at"));
		assertEquals("CUSTOMER_REQUEST", after.get("cancellation_reason"));
		assertEquals("PREPARING", jdbc.queryForObject("""
				SELECT from_status FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND h.to_status = 'CANCELLED'
				""", String.class, a.restaurant()));
	}

	@Test
	void aRefusalOnlyMarksTheRequestRejectedAndLeavesTheOrderExactlyAsItWas() {
		Asked a = asked();
		Map<String, Object> before = order(a);
		Draft refusal = reject(a, 1).reason("ORDER_ALREADY_SERVED");

		assertEquals(new DecisionResult(DecisionOutcome.REQUEST_REJECTED, false), apply(refusal));

		assertEquals(before, order(a), "a refusal changes nothing about the order, not even the version Kitchen knows");
		Map<String, Object> req = request(a);
		assertEquals("REJECTED", req.get("status"));
		assertEquals("ORDER_ALREADY_SERVED", req.get("decision_reason_code"));
		assertEquals(refusal.eventId(), req.get("decision_event_id"));
		assertNotNull(req.get("decided_at"));
		assertEquals("REQUEST_REJECTED",
				jdbc.queryForObject("SELECT outcome FROM inbox_events WHERE restaurant_id = ?", String.class, a.restaurant()));
	}

	@Test
	void anApprovalForAnOrderThatBecameReadyIsNeverForcedAndIsRecordedForReconciliation() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		preparation.startPreparation(restaurant, actor, UUID.randomUUID(), orderId);
		UUID requestId = cancellations.request(restaurant, actor, UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.OTHER, null)).request().getId();
		preparation.markReady(restaurant, actor, UUID.randomUUID(), orderId);
		Asked a = new Asked(restaurant, orderId, requestId, actor);
		Map<String, Object> before = order(a);
		Draft decision = approve(a, 4);

		assertEquals(new DecisionResult(DecisionOutcome.NEEDS_RECONCILIATION, false), apply(decision));

		assertEquals(before, order(a), "READY stays READY, its ready event and history are untouched");
		assertEquals("READY", order(a).get("status"));
		assertEquals("REQUESTED", request(a).get("status"), "the request is not shown as approved while the order is not cancelled");
		assertEquals("NEEDS_RECONCILIATION",
				jdbc.queryForObject("SELECT outcome FROM inbox_events WHERE restaurant_id = ?", String.class, restaurant));
		// The same event again is a repeat, not a second record.
		assertEquals(new DecisionResult(DecisionOutcome.NEEDS_RECONCILIATION, true), apply(decision));
		assertEquals(1, inbox(restaurant));
	}

	// ---- repeats, old events, ordering ----------------------------------------------------------------------------------

	@Test
	void theSameEventAgainChangesNothingAndIsReportedAsARepeat() {
		Asked a = asked();
		Draft decision = approve(a, 2);
		apply(decision);
		Map<String, Object> once = order(a);

		DecisionResult again = apply(decision);

		assertEquals(new DecisionResult(DecisionOutcome.CANCELLED, true), again);
		assertEquals(once, order(a), "no second history entry, no second change");
		assertEquals(1, inbox(a.restaurant()));
	}

	@Test
	void anApprovalThatIsNotNewerThanWhatKitchenKnowsIsStaleAndCancelsNothing() {
		Asked a = asked();

		assertEquals(DecisionOutcome.STALE, apply(approve(a, 1)).outcome(), "equal to the known version");
		assertEquals(DecisionOutcome.STALE, apply(approve(a, 0)).outcome(), "older than the known version");

		assertEquals("WAITING", order(a).get("status"));
		assertEquals("REQUESTED", request(a).get("status"));
		assertEquals(2, inbox(a.restaurant()));
		// A genuinely newer decision still works afterwards: stale events do not block the request.
		assertEquals(DecisionOutcome.CANCELLED, apply(approve(a, 2)).outcome());
	}

	@Test
	void aRefusalMayCarryTheCurrentVersionButNotAnOlderOne() {
		Asked a = asked();

		assertEquals(DecisionOutcome.STALE, apply(reject(a, 0)).outcome());
		assertEquals("REQUESTED", request(a).get("status"));
		assertEquals(DecisionOutcome.REQUEST_REJECTED, apply(reject(a, 1)).outcome());
	}

	@Test
	void aLateDecisionAfterTheRequestWasDecidedChangesNothing() {
		Asked a = asked();
		apply(approve(a, 3));
		Map<String, Object> cancelled = order(a);

		DecisionResult lateRefusal = apply(reject(a, 2));
		DecisionResult lateApproval = apply(approve(a, 9));

		assertEquals(DecisionOutcome.ALREADY_DECIDED, lateRefusal.outcome());
		assertEquals(DecisionOutcome.ALREADY_DECIDED, lateApproval.outcome());
		assertEquals(cancelled, order(a));
		assertEquals("APPROVED", request(a).get("status"));
		assertEquals(3, inbox(a.restaurant()));
	}

	@Test
	void aVersionJumpIsAcceptedBecauseKitchenDoesNotSeeEveryChangeOfTheOrder() {
		Asked a = asked();

		assertEquals(DecisionOutcome.CANCELLED, apply(approve(a, 40)).outcome());
		assertEquals(40L, ((Number) order(a).get("source_order_version")).longValue());
	}

	// ---- what is refused and not recorded ---------------------------------------------------------------------------

	@Test
	void unknownOrdersRequestsAndOtherRestaurantsAreRefusedWithoutAnyTrace() {
		Asked a = asked();
		Asked other = asked();
		Map<String, Object> before = order(a);

		for (Draft draft : List.of(
				approve(a, 2).order(UUID.randomUUID()),
				approve(a, 2).request(UUID.randomUUID()),
				approve(a, 2).restaurant(other.restaurant()),
				approve(a, 2).request(other.requestId()),
				approve(a, 2).restaurant(other.restaurant()).order(a.orderId()).request(a.requestId()))) {
			assertEquals(DecisionOutcome.UNKNOWN, apply(draft).outcome());
		}

		assertEquals(before, order(a));
		assertEquals("WAITING", order(other).get("status"));
		assertEquals("REQUESTED", request(a).get("status"));
		assertEquals("REQUESTED", request(other).get("status"));
		assertEquals(0, inbox(a.restaurant()) + inbox(other.restaurant()));
	}

	@Test
	void malformedUnsupportedOrImplausibleDecisionsAreRefusedWithoutAnyTrace() {
		Asked a = asked();
		Instant receivedAt = ((Timestamp) jdbc.queryForObject("SELECT received_at FROM kitchen_orders WHERE order_id = ?", Timestamp.class,
				a.orderId())).toInstant();

		for (Draft draft : List.of(
				approve(a, 2).schema(2), approve(a, 2).type("OrderCancellationRejected"), approve(a, 2).version(-1),
				approve(a, 2).reason("lower case"), approve(a, 2).eventId(null), approve(a, 2).at(null),
				approve(a, 2).at(receivedAt.minusSeconds(60)), approve(a, 2).at(Instant.now().plus(1, ChronoUnit.HOURS)))) {
			assertEquals(DecisionOutcome.INVALID, apply(draft).outcome());
		}
		assertEquals(DecisionOutcome.INVALID, decisions.apply(SOURCE_ORDER, null).outcome());

		assertEquals("WAITING", order(a).get("status"));
		assertEquals("REQUESTED", request(a).get("status"));
		assertEquals(0, inbox(a.restaurant()));
	}

	@Test
	void aSenderThatIsNotTheVerifiedOrderServiceIsNeverTrustedWhateverTheBodySays() {
		Asked a = asked();
		OrderCancellationDecisionMessage message = approve(a, 2).message();

		for (String sender : new String[] { null, "", "order", "ORDER ", "MENU", "KITCHEN", "ORDER-SERVICE" }) {
			assertEquals(DecisionOutcome.SOURCE_NOT_TRUSTED, decisions.apply(sender, message).outcome(), String.valueOf(sender));
		}

		assertEquals("WAITING", order(a).get("status"));
		assertEquals("REQUESTED", request(a).get("status"));
		assertEquals(0, inbox(a.restaurant()));
	}

	@Test
	void anEventIdReusedForAnotherEventIsRefused() {
		Asked first = asked();
		Asked second = asked();
		Draft decision = approve(first, 2);
		apply(decision);

		DecisionResult reuse = apply(approve(second, 2).eventId(decision.eventId()));

		assertEquals(new DecisionResult(DecisionOutcome.INVALID, false), reuse);
		assertEquals("WAITING", order(second).get("status"));
		assertEquals("REQUESTED", request(second).get("status"));
		assertEquals(1, inbox(first.restaurant()) + inbox(second.restaurant()));
	}

	@Test
	void whenNoDecisionEverArrivesTheOrderIsNeverCancelled() {
		Asked a = asked();
		Map<String, Object> before = order(a);

		// Time passes, requests are repeated, preparation goes on: only a decision can cancel.
		assertNotEquals("CANCELLED", order(a).get("status"));
		assertEquals(before, order(a));
		assertEquals("REQUESTED", request(a).get("status"));
		assertNull(order(a).get("cancelled_at"));
	}

	// ---- parallel consumers ---------------------------------------------------------------------------------------------

	private List<DecisionResult> fire(int threads, IntFunction<Draft> draft) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<DecisionResult>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int n = i;
			futures.add(pool.submit(() -> {
				start.await();
				return apply(draft.apply(n));
			}));
		}
		start.countDown();
		List<DecisionResult> results = new ArrayList<>();
		for (Future<DecisionResult> future : futures) {
			results.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return results;
	}

	@Test
	void parallelConsumersOfTheSameEventApplyItExactlyOnce() throws Exception {
		Asked a = asked();
		Draft decision = approve(a, 2);

		List<DecisionResult> results = fire(8, n -> decision);

		assertEquals(1, results.stream().filter(r -> !r.duplicate()).count(), "exactly one consumer applied it: " + results);
		assertEquals(7, results.stream().filter(DecisionResult::duplicate).count());
		assertTrue(results.stream().allMatch(r -> r.outcome() == DecisionOutcome.CANCELLED));
		assertEquals(2L, ((Number) order(a).get("history")).longValue(), "initial WAITING and one CANCELLED, never two");
		assertEquals(1, inbox(a.restaurant()));
	}

	@Test
	void anApprovalAndARefusalRacingForOneRequestLetExactlyOneWin() throws Exception {
		Asked a = asked();

		List<DecisionResult> results = fire(2, n -> n == 0 ? approve(a, 2) : reject(a, 2));

		assertEquals(1, results.stream().filter(r -> r.outcome() == DecisionOutcome.CANCELLED
				|| r.outcome() == DecisionOutcome.REQUEST_REJECTED).count(), results.toString());
		assertEquals(1, results.stream().filter(r -> r.outcome() == DecisionOutcome.ALREADY_DECIDED).count(), results.toString());
		String status = (String) request(a).get("status");
		assertEquals("APPROVED".equals(status) ? "CANCELLED" : "WAITING", order(a).get("status"), "the order follows the one decision that won");
		assertEquals(2, inbox(a.restaurant()));
	}
}
