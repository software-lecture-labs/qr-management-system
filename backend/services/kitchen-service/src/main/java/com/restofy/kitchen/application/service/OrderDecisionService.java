package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.InboxEvent;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.InboxEventRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Applies the Order service's decision on a cancellation request. This is the ONLY way a kitchen order becomes CANCELLED:
 * Kitchen never cancels on its own, and a request, an acknowledgement of one or a missing answer cancel nothing.
 *
 * <p>There is NO production way in: no endpoint and no listener exist, because the sender's verified identity (D-09) and
 * the transport (D-10) are undecided. A future adapter must authenticate the Order service and then call
 * {@link #apply} with that verified sender; here only tests do. The sender is never read from the message.
 *
 * <p>One local transaction (the order row is locked first) holds the inbox row, the request, and for an approval the order,
 * its status history and its version, so nothing is ever half applied and the same event can never be applied twice.
 * Rules, in the order they are checked: trusted sender and well-formed content; the order and the request exist for that
 * restaurant and belong together; the event was not processed before; the time is plausible; the request is still
 * undecided; the version is new (an approval needs a strictly greater version than Kitchen knows, a refusal changes no
 * order and so may repeat the current one); then a refusal only marks the request REJECTED, an approval cancels a WAITING or
 * PREPARING order, and an approval for an order that is already READY is not forced: it is recorded as NEEDS_RECONCILIATION
 * and the request stays REQUESTED (D-02).
 */
@Service
public class OrderDecisionService {

	private static final Logger log = LoggerFactory.getLogger(OrderDecisionService.class);

	/** The only sender Kitchen accepts decisions from. */
	public static final String SOURCE_ORDER = "ORDER";

	/** What happened to one event; {@code duplicate} means it had been processed before and nothing changed now. */
	public record DecisionResult(DecisionOutcome outcome, boolean duplicate) {
	}

	private final KitchenOrderRepository orders;
	private final CancellationRequestRepository requests;
	private final KitchenStatusHistoryRepository history;
	private final InboxEventRepository inbox;
	private final Clock clock;

	public OrderDecisionService(KitchenOrderRepository orders, CancellationRequestRepository requests,
			KitchenStatusHistoryRepository history, InboxEventRepository inbox, Clock clock) {
		this.orders = orders;
		this.requests = requests;
		this.history = history;
		this.inbox = inbox;
		this.clock = clock;
	}

	@Transactional
	public DecisionResult apply(String sourceService, OrderCancellationDecisionMessage message) {
		if (!SOURCE_ORDER.equals(sourceService)) {
			log.warn("Order decision refused: sender is not the verified Order service");
			return refuse(DecisionOutcome.SOURCE_NOT_TRUSTED);
		}
		String problem = message == null ? "MISSING_MESSAGE" : message.shapeProblem();
		if (problem != null) {
			log.warn("Order decision refused: invalid content ({})", problem);
			return refuse(DecisionOutcome.INVALID);
		}
		KitchenOrder order = orders.findForUpdate(message.restaurantId(), message.orderId()).orElse(null);
		if (order == null) {
			log.warn("Order decision refused: unknown order, eventId={}", message.eventId());
			return refuse(DecisionOutcome.UNKNOWN);
		}
		InboxEvent.Received received = new InboxEvent.Received(message.eventId(), message.eventType(), message.schemaVersion(),
				message.restaurantId(), message.orderId(), message.requestId(), message.orderVersion(), message.decision(),
				message.decidedAt(), message.correlationId());

		InboxEvent seen = inbox.findBySourceServiceAndEventId(sourceService, message.eventId()).orElse(null);
		if (seen != null) {
			if (!seen.isSameEventAs(received)) {
				log.warn("Order decision refused: event id {} was used for another event", message.eventId());
				return refuse(DecisionOutcome.INVALID);
			}
			return new DecisionResult(seen.getOutcome(), true);
		}
		CancellationRequest request = requests.findById(message.requestId())
				.filter(r -> r.getRestaurantId().equals(message.restaurantId()) && r.getOrderId().equals(message.orderId())).orElse(null);
		if (request == null) {
			log.warn("Order decision refused: unknown request, eventId={}", message.eventId());
			return refuse(DecisionOutcome.UNKNOWN);
		}
		Instant now = clock.instant();
		Instant latestPlausible = now.plus(OrderCancellationDecisionMessage.MAX_CLOCK_SKEW);
		if (message.decidedAt().isBefore(order.getReceivedAt()) || message.decidedAt().isAfter(latestPlausible)) {
			log.warn("Order decision refused: implausible time, eventId={}", message.eventId());
			return refuse(DecisionOutcome.INVALID);
		}

		DecisionOutcome outcome = decide(order, request, message);
		inbox.saveAndFlush(InboxEvent.received(sourceService, received, outcome, now));
		log.info("Order decision {}: restaurantId={} orderId={} requestId={} eventId={}", outcome, message.restaurantId(),
				message.orderId(), message.requestId(), message.eventId());
		return new DecisionResult(outcome, false);
	}

	private DecisionOutcome decide(KitchenOrder order, CancellationRequest request, OrderCancellationDecisionMessage message) {
		if (request.getStatus() != CancellationRequestStatus.REQUESTED) {
			return DecisionOutcome.ALREADY_DECIDED;
		}
		boolean approval = message.decision() == OrderDecision.APPROVED;
		// PROVISIONAL (product owner, until the real Order contract is agreed): a refusal changes nothing at the Order service,
		// so it may carry the version Kitchen already knows; an approval changed the order, so only a strictly newer version
		// is a new fact. Older versions are stale either way.
		if (approval ? message.orderVersion() <= order.getSourceOrderVersion() : message.orderVersion() < order.getSourceOrderVersion()) {
			return DecisionOutcome.STALE;
		}
		CancellationRequest.Decision decision = new CancellationRequest.Decision(message.decidedAt(), message.reasonCode(),
				message.orderVersion(), message.eventId());
		if (!approval) {
			request.reject(decision);
			return DecisionOutcome.REQUEST_REJECTED;
		}
		if (!order.canBeCancelledByDecision()) {
			return DecisionOutcome.NEEDS_RECONCILIATION;
		}
		KitchenOrderStatus from = order.getStatus();
		order.cancelByDecision(message.decidedAt(), request.getReasonCode().name(), message.orderVersion());
		orders.saveAndFlush(order);
		history.saveAndFlush(KitchenStatusHistory.cancelled(order, from, message.eventId(), request.getReasonCode().name(),
				order.getCancelledAt()));
		request.approve(decision);
		requests.saveAndFlush(request);
		return DecisionOutcome.CANCELLED;
	}

	private static DecisionResult refuse(DecisionOutcome outcome) {
		return new DecisionResult(outcome, false);
	}
}
