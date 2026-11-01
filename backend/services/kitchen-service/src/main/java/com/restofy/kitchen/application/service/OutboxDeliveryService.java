package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.DeliveryOutcome;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs delivery attempts against an {@link EventSink}. One attempt = claim (short transaction, row lock, lease)
 * -> call the sink with NO transaction and NO lock open -> record the result (short transaction). Nothing in here
 * schedules itself and nothing wires a real sink: until the transport is decided (D-10) it is only invoked by tests.
 * It never touches the order, its history or its READY status: a delivery problem cannot undo "ready".
 */
@Service
public class OutboxDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryService.class);

	/** How long a claimed delivery stays invisible to other workers; after that it is due again (crashed worker). */
	static final Duration LEASE = Duration.ofSeconds(60);
	private static final int MAX_ERROR_CODE = 80;

	private final OutboxDeliveryRepository deliveries;
	private final OutboxEventRepository events;
	private final KitchenEventRecorder recorder;
	private final TransactionTemplate tx;
	private final Clock clock;

	public OutboxDeliveryService(OutboxDeliveryRepository deliveries, OutboxEventRepository events,
			KitchenEventRecorder recorder, TransactionTemplate tx, Clock clock) {
		this.deliveries = deliveries;
		this.events = events;
		this.recorder = recorder;
		this.tx = tx;
		this.clock = clock;
	}

	/** Same as {@link #deliverDue(EventSink, int)} for the Order service (a lambda is enough for an Order sink). */
	public int deliverDue(OrderEventSink sink, int limit) {
		return deliverDue((EventSink) sink, limit);
	}

	/** Same as {@link #attempt(UUID, EventSink)} for the Order service (a lambda is enough for an Order sink). */
	public Optional<DeliveryStatus> attempt(UUID deliveryId, OrderEventSink sink) {
		return attempt(deliveryId, (EventSink) sink);
	}

	/** Attempts every delivery to the sink's destination that is due now, oldest first, at most {@code limit}. Returns the attempts made. */
	public int deliverDue(EventSink sink, int limit) {
		List<UUID> due = deliveries.findDueIds(clock.instant(), sink.destination(), PageRequest.of(0, limit));
		int attempts = 0;
		for (UUID id : due) {
			if (attempt(id, sink).isPresent()) {
				attempts++;
			}
		}
		return attempts;
	}

	/**
	 * One attempt for one delivery. Empty if the delivery is not due, already final, or claimed by another worker
	 * (the sink is not called). Otherwise returns the delivery's status after the result was recorded.
	 */
	public Optional<DeliveryStatus> attempt(UUID deliveryId, EventSink sink) {
		Optional<OutboundMessage> message = tx.execute(status -> claim(deliveryId, sink.destination()));
		if (message.isEmpty()) {
			return Optional.empty();
		}
		EventSink.Result result = callSink(sink, message.get());
		return Optional.of(tx.execute(status -> recordResult(deliveryId, result)));
	}

	/** A delivery is only ever handed to the sink of its own destination; for any other sink it is as if it were not there. */
	private Optional<OutboundMessage> claim(UUID deliveryId, String destination) {
		Optional<OutboxDelivery> found = deliveries.findForUpdate(deliveryId);
		if (found.isEmpty() || !destination.equals(found.get().getDestination()) || !found.get().claim(clock.instant(), LEASE)) {
			return Optional.empty();
		}
		OutboxEvent event = events.findById(found.get().getEventId()).orElseThrow();
		return Optional.of(recorder.toMessage(event));
	}

	/** An exception, a missing or an inconsistent result is an unknown outcome: retry, never success. */
	private EventSink.Result callSink(EventSink sink, OutboundMessage message) {
		try {
			EventSink.Result result = sink.deliver(message);
			if (result == null || result.outcome() == null) {
				return EventSink.Result.retry("NO_RESULT");
			}
			return result;
		} catch (RuntimeException _) {
			return EventSink.Result.retry("SINK_EXCEPTION");
		}
	}

	private DeliveryStatus recordResult(UUID deliveryId, EventSink.Result result) {
		OutboxDelivery delivery = deliveries.findForUpdate(deliveryId).orElseThrow();
		delivery.recordAttempt(result.outcome(), safeCode(result), clock.instant());
		log.info("Outbox delivery {} -> {} (attempt {}, outcome {})", deliveryId, delivery.getStatus(),
				delivery.getAttemptCount(), result.outcome());
		return delivery.getStatus();
	}

	private static String safeCode(EventSink.Result result) {
		if (result.outcome() == DeliveryOutcome.DELIVERED || result.errorCode() == null) {
			return null;
		}
		String code = result.errorCode().replaceAll("[^A-Za-z0-9_.-]", "_");
		return code.length() > MAX_ERROR_CODE ? code.substring(0, MAX_ERROR_CODE) : code;
	}
}
