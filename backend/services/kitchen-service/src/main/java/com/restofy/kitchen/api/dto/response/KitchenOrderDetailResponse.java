package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.OptionAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only kitchen view of one order. Table data is the snapshot Order sent at acceptance (not live QR data).
 * Internal ids, the tenant id, versions and event ids are intentionally not exposed. No prices are carried.
 * {@code receivedAt} is when the Kitchen service stored the order (not Order's acceptance time). Empty values are
 * always serialized as explicit JSON {@code null} (never omitted), independent of global Jackson settings.
 * {@code cancelledAt} and {@code cancellationReason} are set only once the Order service approved a cancellation (the
 * time is its decision time, the reason the kitchen's reason code); a pending cancellation REQUEST changes neither.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KitchenOrderDetailResponse(
		UUID orderId,
		String orderNumber,
		String tableNumber,
		UUID tableSessionId,
		KitchenOrderStatus status,
		Instant receivedAt,
		Instant startedAt,
		Instant readyAt,
		Instant cancelledAt,
		String cancellationReason,
		List<Item> items) {

	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Item(
			UUID orderItemId,
			UUID productId,
			String productName,
			int quantity,
			String note,
			List<Option> options) {
	}

	/** An ingredient added to ({@code ADD}) or removed from ({@code REMOVE}) the base product. */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Option(
			OptionAction action,
			UUID optionId,
			String name,
			int quantity,
			String note) {
	}
}
