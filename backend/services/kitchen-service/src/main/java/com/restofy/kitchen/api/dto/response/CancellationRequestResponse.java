package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The state of a cancellation REQUEST - never of the order. {@code status=REQUESTED} means the Order service has not
 * decided: the order is NOT cancelled and keeps its preparation status. The decision fields stay {@code null} until
 * the Order service has decided.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CancellationRequestResponse(
		UUID requestId,
		UUID orderId,
		CancellationRequestStatus status,
		CancellationReason reasonCode,
		String reasonText,
		UUID productId,
		UUID orderItemId,
		Instant requestedAt,
		UUID requestedByUserId,
		Instant decidedAt,
		String decisionReasonCode) {

	public static CancellationRequestResponse from(CancellationRequest request) {
		return new CancellationRequestResponse(request.getId(), request.getOrderId(), request.getStatus(), request.getReasonCode(),
				request.getReasonText(), request.getProductId(), request.getOrderItemId(), request.getRequestedAt(),
				request.getRequestedByUserId(), request.getDecidedAt(), request.getDecisionReasonCode());
	}
}
