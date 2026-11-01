package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RecordWasteRequest;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.api.dto.response.WasteRecordResponse;
import com.restofy.kitchen.domain.entity.WasteRecord;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderItemRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.WasteRecordRepository;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Manual waste ("fire") entries. An entry changes no stock quantity and closes no product. The required command id
 * (the Idempotency-Key) makes a retry safe: the same entry sent again is returned, not stored twice; the same key for
 * a different entry is a conflict. The product name is only filled from a name Kitchen itself received with an order of
 * this restaurant. Nothing here talks to another service.
 */
@Service
public class WasteRecordService {

	private static final String COMMAND_INDEX = "uq_waste_records_command";

	/** The stored entry and whether this call created it (false = a verified repeat). */
	public record Recorded(WasteRecord wasteRecord, boolean created) {
	}

	private final WasteRecordRepository records;
	private final KitchenOrderRepository orders;
	private final KitchenOrderItemRepository items;
	private final TransactionTemplate tx;
	private final Clock clock;

	public WasteRecordService(WasteRecordRepository records, KitchenOrderRepository orders, KitchenOrderItemRepository items,
			TransactionTemplate tx, Clock clock) {
		this.records = records;
		this.orders = orders;
		this.items = items;
		this.tx = tx;
		this.clock = clock;
	}

	public Recorded create(UUID restaurantId, UUID actorUserId, UUID commandId, RecordWasteRequest request) {
		try {
			return tx.execute(status -> recordOnce(restaurantId, actorUserId, commandId, request));
		} catch (DataIntegrityViolationException e) {
			if (!isCommandIndex(e)) {
				throw e;
			}
			// A parallel request with the same key won the insert (PostgreSQL aborted this transaction): look again.
			return tx.execute(status -> recordOnce(restaurantId, actorUserId, commandId, request));
		}
	}

	private Recorded recordOnce(UUID restaurantId, UUID actorUserId, UUID commandId, RecordWasteRequest request) {
		WasteRecord existing = records.findByRestaurantIdAndCommandId(restaurantId, commandId).orElse(null);
		if (existing != null) {
			if (!existing.isSameEntry(request.productId(), request.quantity(), request.reasonCode(), request.description(),
					request.orderId(), actorUserId)) {
				throw new CommandIdConflictException();
			}
			return new Recorded(existing, false);
		}
		if (request.orderId() != null && orders.findByRestaurantIdAndOrderId(restaurantId, request.orderId()).isEmpty()) {
			throw new KitchenOrderNotFoundException();
		}
		List<String> names = items.latestNames(restaurantId, request.productId(), PageRequest.of(0, 1));
		WasteRecord.NewEntry entry = new WasteRecord.NewEntry(restaurantId, commandId, request.productId(),
				names.isEmpty() ? null : names.get(0), request.quantity(), request.reasonCode(), request.description(), request.orderId(),
				clock.instant().truncatedTo(ChronoUnit.MICROS), actorUserId);
		WasteRecord created = WasteRecord.create(entry);
		return new Recorded(records.saveAndFlush(created), true);
	}

	/** Newest first (then by id) so paging is deterministic. Each bound is optional; [from, to). */
	@Transactional(readOnly = true)
	public PageResponse<WasteRecordResponse> list(UUID restaurantId, Instant from, Instant to, UUID productId, int page, int size) {
		var result = records.findAll(WasteRecordRepository.filter(restaurantId, from, to, productId),
				PageRequest.of(page, size, Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("id"))));
		return PageResponse.from(result.map(WasteRecordResponse::from));
	}

	private static boolean isCommandIndex(DataIntegrityViolationException e) {
		return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains(COMMAND_INDEX);
	}
}
