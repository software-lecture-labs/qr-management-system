package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.response.KitchenOrderDetailResponse;
import com.restofy.kitchen.api.dto.response.KitchenOrderSummaryResponse;
import com.restofy.kitchen.api.dto.response.PageResponse;
import com.restofy.kitchen.application.mapper.OptionsSnapshotCodec;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenOrderItem;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.KitchenStatusFilter;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KitchenOrderQueryService {

	/** Work queue order: oldest first, id as deterministic tie-breaker. Not client controllable. */
	private static final Sort QUEUE_ORDER = Sort.by(Sort.Direction.ASC, "receivedAt").and(Sort.by(Sort.Direction.ASC, "id"));

	private final KitchenOrderRepository orders;
	private final OptionsSnapshotCodec codec;

	public KitchenOrderQueryService(KitchenOrderRepository orders, OptionsSnapshotCodec codec) {
		this.orders = orders;
		this.codec = codec;
	}

	/** Lists the caller's restaurant queue. {@code receivedFrom} is inclusive, {@code receivedTo} exclusive. */
	@Transactional(readOnly = true)
	public PageResponse<KitchenOrderSummaryResponse> list(UUID restaurantId, KitchenStatusFilter filter,
			Instant receivedFrom, Instant receivedTo, int page, int size) {
		return PageResponse.from(orders
				.findAll(KitchenOrderSpecifications.queue(restaurantId, filter.statuses(), receivedFrom, receivedTo),
						PageRequest.of(page, size, QUEUE_ORDER))
				.map(KitchenOrderQueryService::toSummary));
	}

	/** Detail of one order, scoped to the caller's restaurant; other tenants' orders look like missing ones. */
	@Transactional(readOnly = true)
	public KitchenOrderDetailResponse get(UUID restaurantId, UUID orderId) {
		return orders.findByRestaurantIdAndOrderId(restaurantId, orderId)
				.map(this::toDetail)
				.orElseThrow(KitchenOrderNotFoundException::new);
	}

	/** Maps a loaded order to its read-only detail view; call inside the transaction that loaded it (lazy items). */
	public KitchenOrderDetailResponse toDetail(KitchenOrder o) {
		List<KitchenOrderDetailResponse.Item> items = o.getItems().stream()
				.map(i -> new KitchenOrderDetailResponse.Item(i.getOrderItemId(), i.getProductId(),
						i.getProductNameSnapshot(), i.getQuantity(), i.getNoteSnapshot(), options(i)))
				.toList();
		return new KitchenOrderDetailResponse(o.getOrderId(), o.getOrderNumberSnapshot(), o.getTableNumberSnapshot(),
				o.getTableSessionId(), o.getStatus(), o.getReceivedAt(), o.getStartedAt(), o.getReadyAt(), o.getCancelledAt(),
				o.getCancellationReason(), items);
	}

	private List<KitchenOrderDetailResponse.Option> options(KitchenOrderItem item) {
		return codec.fromJson(item.getOptionsSnapshot()).options().stream()
				.map(o -> new KitchenOrderDetailResponse.Option(o.action(), o.optionId(), o.name(), o.quantity(), o.note()))
				.toList();
	}

	private static KitchenOrderSummaryResponse toSummary(KitchenOrder o) {
		return new KitchenOrderSummaryResponse(o.getOrderId(), o.getOrderNumberSnapshot(), o.getTableNumberSnapshot(),
				o.getTableSessionId(), o.getStatus(), o.getReceivedAt(), o.getStatus() == KitchenOrderStatus.WAITING,
				o.getItems().size(), o.getCancelledAt(), o.getCancellationReason());
	}
}
