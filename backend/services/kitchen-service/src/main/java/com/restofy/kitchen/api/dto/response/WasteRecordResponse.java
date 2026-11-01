package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.domain.entity.WasteRecord;
import com.restofy.kitchen.domain.enums.WasteReason;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored waste entry. {@code productName} is only set when Kitchen itself received the product's name with an order
 * of this restaurant, otherwise an explicit {@code null}; it is never made up. {@code recordedAt} / {@code recordedByUserId}
 * are the server's record, not the client's.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WasteRecordResponse(
		UUID id,
		UUID productId,
		String productName,
		int quantity,
		WasteReason reasonCode,
		String description,
		UUID orderId,
		Instant recordedAt,
		UUID recordedByUserId) {

	public static WasteRecordResponse from(WasteRecord wasteRecord) {
		return new WasteRecordResponse(wasteRecord.getId(), wasteRecord.getProductId(), wasteRecord.getProductNameSnapshot(),
				wasteRecord.getQuantity(), wasteRecord.getReasonCode(), wasteRecord.getDescription(), wasteRecord.getOrderId(),
				wasteRecord.getRecordedAt(), wasteRecord.getRecordedByUserId());
	}
}
