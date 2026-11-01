package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.CancellationAlreadyRequestedException;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.InvalidStockReferenceException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The kitchen's request to cancel a whole order. A request is only a RECORD plus an outbox event: the kitchen order's
 * preparation status, its history and its READY event are never touched, and nothing here says the order is cancelled
 * (only the Order service decides that). One local transaction holds the order's row lock, the request and its event,
 * so a request exists if and only if its event does, and two staff members cannot open two requests for one order.
 *
 * <p>Rules: only WAITING and PREPARING orders can be asked for (READY and CANCELLED are 409 until the open decision D-02
 * says otherwise); an order has at most one undecided request; the same Idempotency-Key with the same content is a
 * repeat (the stored request again, no second request or event), with other content a conflict.
 */
@Service
public class CancellationRequestService {

	private static final Logger log = LoggerFactory.getLogger(CancellationRequestService.class);
	private static final String COMMAND_INDEX = "uq_cancellation_requests_command";
	private static final String OPEN_INDEX = "uq_cancellation_requests_open";

	/** The stored request and whether this call created it (false = a verified repeat). */
	public record Requested(CancellationRequest request, boolean created) {
	}

	private final KitchenOrderRepository orders;
	private final CancellationRequestRepository requests;
	private final KitchenEventRecorder events;
	private final Clock clock;

	public CancellationRequestService(KitchenOrderRepository orders, CancellationRequestRepository requests,
			KitchenEventRecorder events, Clock clock) {
		this.orders = orders;
		this.requests = requests;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Requested request(UUID restaurantId, UUID actorUserId, UUID commandId, UUID orderId, RequestOrderCancellationRequest body) {
		checkStockReferenceShape(body);
		// Locks the order row: preparation commands and other cancellation requests for it are serialized.
		KitchenOrder order = orders.findForUpdate(restaurantId, orderId).orElseThrow(KitchenOrderNotFoundException::new);

		CancellationRequest known = requests.findByRestaurantIdAndCommandId(restaurantId, commandId).orElse(null);
		if (known != null) {
			if (!known.isSameRequest(orderId, body.reasonCode(), body.reasonText(), body.productId(), body.orderItemId(), actorUserId)) {
				throw new CommandIdConflictException();
			}
			log.info("Cancellation request REPEAT: restaurantId={} orderId={} actor={}", restaurantId, orderId, actorUserId);
			return new Requested(known, false);
		}
		if (order.getStatus() != KitchenOrderStatus.WAITING && order.getStatus() != KitchenOrderStatus.PREPARING) {
			throw new InvalidKitchenTransitionException(order.getStatus(), KitchenOrderStatus.CANCELLED);
		}
		if (requests.findByRestaurantIdAndOrderIdAndStatus(restaurantId, orderId, CancellationRequestStatus.REQUESTED).isPresent()) {
			throw new CancellationAlreadyRequestedException();
		}

		checkStockReferenceBelongsToOrder(order, body);

		CancellationRequest created = CancellationRequest.open(new CancellationRequest.NewRequest(restaurantId, orderId, commandId,
				body.reasonCode(), body.reasonText(), body.productId(), body.orderItemId(), actorUserId,
				clock.instant().truncatedTo(ChronoUnit.MICROS)));
		save(created);
		events.recordCancellationRequested(created, order);
		log.info("Cancellation request CREATED: restaurantId={} orderId={} actor={} reason={}", restaurantId, orderId, actorUserId,
				body.reasonCode());
		return new Requested(created, true);
	}

	/**
	 * The order's whole request history, newest first: what was asked, by whom, why, and (once the Order service has
	 * decided) the decision. A pending REQUESTED entry and a final cancellation are different things and stay visibly so.
	 * 404 for an unknown order and for another restaurant's order alike.
	 */
	@Transactional(readOnly = true)
	public List<CancellationRequest> history(UUID restaurantId, UUID orderId) {
		orders.findByRestaurantIdAndOrderId(restaurantId, orderId).orElseThrow(KitchenOrderNotFoundException::new);
		return requests.findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(restaurantId, orderId);
	}

	/**
	 * A stock (OUT_OF_STOCK) request names the product that ran out; any other reason names none. A line reference
	 * only makes sense together with its product. This needs no database access.
	 */
	private static void checkStockReferenceShape(RequestOrderCancellationRequest body) {
		boolean stock = body.reasonCode() == CancellationReason.OUT_OF_STOCK;
		if (stock && body.productId() == null) {
			throw new InvalidStockReferenceException("An out-of-stock cancellation needs the product that is out of stock");
		}
		if (!stock && (body.productId() != null || body.orderItemId() != null)) {
			throw new InvalidStockReferenceException("A product reference is only allowed with the reason OUT_OF_STOCK");
		}
	}

	/**
	 * The product (and the line, if one is named) must be part of THIS order's own snapshot. Nothing else is asked: the
	 * Menu service is not consulted, and the answer is the same whether the id exists in another order or restaurant.
	 */
	private static void checkStockReferenceBelongsToOrder(KitchenOrder order, RequestOrderCancellationRequest body) {
		if (body.productId() == null) {
			return;
		}
		boolean part = order.getItems().stream().anyMatch(item -> item.getProductId().equals(body.productId())
				&& (body.orderItemId() == null || item.getOrderItemId().equals(body.orderItemId())));
		if (!part) {
			throw new InvalidStockReferenceException("The product or order line is not part of this order");
		}
	}

	private void save(CancellationRequest request) {
		try {
			requests.saveAndFlush(request);
		} catch (DataIntegrityViolationException e) {
			// The same key raced in for another order, or another request got in first: conflicts, not server errors.
			String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
			if (cause.contains(COMMAND_INDEX)) {
				throw new CommandIdConflictException();
			}
			if (cause.contains(OPEN_INDEX)) {
				throw new CancellationAlreadyRequestedException();
			}
			throw e;
		}
	}
}
