package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.application.mapper.OptionsSnapshotCodec;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import jakarta.validation.ConstraintViolation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores an order accepted by the Order service as a kitchen read-model. There is no HTTP transport for this yet
 * (open decision); the use-case is idempotent per (restaurant, order) and safe under concurrent redelivery.
 */
@Service
public class AcceptedOrderIngestService {

	private static final Logger log = LoggerFactory.getLogger(AcceptedOrderIngestService.class);

	static final String METRIC = "kitchen.order.ingest";
	static final String REJECTED_INVALID = "REJECTED_INVALID";

	private final KitchenOrderRepository orders;
	private final KitchenStatusHistoryRepository history;
	private final OptionsSnapshotCodec codec;
	private final Validator validator;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final MeterRegistry meters;

	public AcceptedOrderIngestService(KitchenOrderRepository orders, KitchenStatusHistoryRepository history,
			OptionsSnapshotCodec codec, Validator validator, TransactionTemplate tx, Clock clock,
			MeterRegistry meters) {
		this.orders = orders;
		this.history = history;
		this.codec = codec;
		this.validator = validator;
		this.tx = tx;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * Every call is counted in {@code kitchen.order.ingest} by outcome, including rejected messages, so messages that
	 * could not be applied (newer version, stale, invalid) stay visible instead of disappearing silently.
	 */
	public IngestOutcome ingest(OrderAcceptedMessage message) {
		try {
			IngestOutcome outcome = process(message);
			count(outcome.name());
			return outcome;
		} catch (InvalidOrderMessageException e) {
			count(REJECTED_INVALID);
			throw e;
		}
	}

	private void count(String outcome) {
		meters.counter(METRIC, "outcome", outcome).increment();
	}

	private IngestOutcome process(OrderAcceptedMessage message) {
		validate(message);

		Optional<KitchenOrder> existing = orders.findByRestaurantIdAndOrderId(message.restaurantId(), message.orderId());
		if (existing.isPresent()) {
			return classifyRedelivery(existing.get(), message);
		}
		try {
			tx.executeWithoutResult(status -> create(message));
			return IngestOutcome.CREATED;
		} catch (DataIntegrityViolationException e) {
			// A concurrent delivery of the same order won the unique constraint; classify against the stored row.
			KitchenOrder stored = orders.findByRestaurantIdAndOrderId(message.restaurantId(), message.orderId())
					.orElseThrow(() -> e);
			return classifyRedelivery(stored, message);
		}
	}

	private void create(OrderAcceptedMessage message) {
		Instant now = clock.instant();
		KitchenOrder order = KitchenOrder.receive(message.restaurantId(), message.orderId(), message.tableSessionId(),
				message.tableNumber().strip(), message.orderNumber().strip(), now, message.aggregateVersion(),
				message.eventId(), now);
		int line = 1;
		for (OrderAcceptedMessage.Item item : message.items()) {
			order.addItem(line++, item.orderItemId(), item.productId(), item.productName().strip(), item.quantity(),
					codec.toJson(item.options()), item.note());
		}
		orders.saveAndFlush(order);
		history.save(KitchenStatusHistory.initial(order, message.eventId(), now));
	}

	private IngestOutcome classifyRedelivery(KitchenOrder stored, OrderAcceptedMessage message) {
		IngestOutcome outcome;
		if (stored.getSourceEventId().equals(message.eventId())
				|| message.aggregateVersion() == stored.getSourceOrderVersion()) {
			outcome = IngestOutcome.DUPLICATE;
		} else if (message.aggregateVersion() < stored.getSourceOrderVersion()) {
			outcome = IngestOutcome.STALE_IGNORED;
		} else {
			outcome = IngestOutcome.NEWER_VERSION_NOT_APPLIED;
		}
		if (outcome == IngestOutcome.NEWER_VERSION_NOT_APPLIED) {
			// Not applied by design (follow-up changes such as partial cancellation are an open decision, D-02):
			// leave a trace with ids and versions only, never order content, so it can be reconciled later.
			log.warn("Newer order version NOT applied: restaurantId={} orderId={} storedVersion={} incomingVersion={} eventId={} correlationId={}",
					stored.getRestaurantId(), stored.getOrderId(), stored.getSourceOrderVersion(),
					message.aggregateVersion(), message.eventId(), message.correlationId());
		} else {
			log.info("Accepted order {} redelivered: {}", stored.getOrderId(), outcome);
		}
		return outcome;
	}

	private void validate(OrderAcceptedMessage message) {
		if (message == null) {
			throw new InvalidOrderMessageException(List.of("message must not be null"));
		}
		List<String> violations = validator.validate(message).stream()
				.map((ConstraintViolation<OrderAcceptedMessage> v) -> v.getPropertyPath() + " " + v.getMessage())
				.sorted()
				.collect(Collectors.toCollection(ArrayList::new));
		if (violations.isEmpty()) {
			Set<UUID> seen = new HashSet<>();
			for (OrderAcceptedMessage.Item item : message.items()) {
				if (!seen.add(item.orderItemId())) {
					violations.add("items orderItemId must be unique within an order");
					break;
				}
				// The same option twice (e.g. ADD and REMOVE) is contradictory and ambiguous for the kitchen.
				Set<UUID> optionIds = new HashSet<>();
				if (item.options().stream().anyMatch(o -> !optionIds.add(o.optionId()))) {
					violations.add("options optionId must be unique within an item");
					break;
				}
			}
		}
		if (!violations.isEmpty()) {
			throw new InvalidOrderMessageException(violations);
		}
	}
}
