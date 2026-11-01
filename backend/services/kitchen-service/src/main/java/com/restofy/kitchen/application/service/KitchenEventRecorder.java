package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.infrastructure.config.CorrelationIdFilter;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Records business events in the caller's transaction (MANDATORY: it refuses to run outside one), so the event exists
 * if and only if the change that caused it was committed. Nothing here contacts another service.
 */
@Component
public class KitchenEventRecorder {

	/** The Order service. Delivery itself is not implemented (D-10). */
	public static final String ORDER_DESTINATION = "ORDER";
	/** The Notification service, which only receives the order-ready event (architecture: Kitchen -> Order, Waiter, Notification). */
	public static final String NOTIFICATION_DESTINATION = "NOTIFICATION";

	private final OutboxEventRepository events;
	private final OutboxDeliveryRepository deliveries;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public KitchenEventRecorder(OutboxEventRepository events, OutboxDeliveryRepository deliveries, JsonMapper jsonMapper,
			Clock clock) {
		this.events = events;
		this.deliveries = deliveries;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	/**
	 * Records {@code KitchenOrderReady} for an order that was just moved to READY (version already incremented).
	 * At most one such event can exist per order; the database enforces it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OutboxEvent recordOrderReady(KitchenOrder order, UUID commandId) {
		var payload = new KitchenOrderReadyMessage.Payload("READY", order.getStartedAt(), order.getReadyAt());
		var aggregate = new OutboxEvent.Aggregate(order.getRestaurantId(), order.getOrderId(), order.getVersion());
		var eventData = new OutboxEvent.EventData(KitchenOrderReadyMessage.TYPE, KitchenOrderReadyMessage.SCHEMA_VERSION,
				MDC.get(CorrelationIdFilter.MDC_KEY), commandId, jsonMapper.writeValueAsString(payload), order.getReadyAt());
		// ONE event, one delivery per interested service: each is acknowledged, retried and given up on by itself.
		return store(OutboxEvent.create(aggregate, eventData, clock.instant()), ORDER_DESTINATION, NOTIFICATION_DESTINATION);
	}

	/**
	 * Records {@code KitchenCancellationRequested} for a request that was just stored. The order itself is NOT
	 * changed by a request, so its version is the one at request time. At most one such event exists per request.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OutboxEvent recordCancellationRequested(CancellationRequest request, KitchenOrder order) {
		var payload = new KitchenCancellationRequestedMessage.Payload(request.getOrderId(), request.getReasonCode().name(),
				request.getProductId(), request.getOrderItemId());
		var aggregate = new OutboxEvent.Aggregate(OutboxEvent.AGGREGATE_CANCELLATION_REQUEST, request.getRestaurantId(),
				request.getId(), order.getVersion());
		var eventData = new OutboxEvent.EventData(KitchenCancellationRequestedMessage.TYPE,
				KitchenCancellationRequestedMessage.SCHEMA_VERSION, MDC.get(CorrelationIdFilter.MDC_KEY), request.getCommandId(),
				jsonMapper.writeValueAsString(payload), request.getRequestedAt());
		// A request is for the Order service only: telling people about a REQUEST needs its own decision.
		return store(OutboxEvent.create(aggregate, eventData, clock.instant()), ORDER_DESTINATION);
	}

	private OutboxEvent store(OutboxEvent event, String... destinations) {
		events.saveAndFlush(event);
		for (String destination : destinations) {
			deliveries.saveAndFlush(OutboxDelivery.pending(event, destination, clock.instant()));
		}
		return event;
	}

	/** The wire form of a stored event, exactly as every delivery attempt (and redelivery) sends it. */
	public OutboundMessage toMessage(OutboxEvent event) {
		try {
			return switch (event.getEventType()) {
				case KitchenOrderReadyMessage.TYPE -> new KitchenOrderReadyMessage(event.getId(), event.getEventType(),
						event.getSchemaVersion(), event.getOccurredAt(), event.getRestaurantId(), event.getAggregateId(),
						event.getAggregateVersion(), event.getCorrelationId(), event.getCausationId(),
						jsonMapper.readValue(event.getPayload(), KitchenOrderReadyMessage.Payload.class));
				case KitchenCancellationRequestedMessage.TYPE -> {
					var payload = jsonMapper.readValue(event.getPayload(), KitchenCancellationRequestedMessage.Payload.class);
					yield new KitchenCancellationRequestedMessage(event.getId(), event.getEventType(), event.getSchemaVersion(),
							event.getOccurredAt(), event.getRestaurantId(), payload.orderId(), event.getAggregateId(),
							event.getAggregateVersion(), event.getCorrelationId(), event.getCausationId(), payload);
				}
				default -> throw new IllegalStateException("Unknown outbox event type " + event.getEventType());
			};
		} catch (JacksonException e) {
			throw new IllegalStateException("Stored outbox payload is not readable", e);
		}
	}
}
