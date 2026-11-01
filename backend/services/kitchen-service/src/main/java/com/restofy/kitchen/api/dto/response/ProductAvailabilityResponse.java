package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.application.contract.ProductAvailabilityState;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A product's availability exactly as the Menu service CONFIRMED it. {@code changedAt} and {@code changedByUserId}
 * are Menu's own record, never the client's. An empty reason text is an explicit {@code null}. {@code published}
 * is the catalogue flag, separate from availability: AVAILABLE with {@code published=false} is not on sale
 * ({@code null} = Menu did not say).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProductAvailabilityResponse(
		UUID productId,
		ProductAvailabilityStatus status,
		String reasonCode,
		String reasonText,
		Instant changedAt,
		UUID changedByUserId,
		long version,
		Boolean published) {

	public static ProductAvailabilityResponse from(ProductAvailabilityState state) {
		return new ProductAvailabilityResponse(state.productId(), state.status(), state.reasonCode(), state.reasonText(),
				state.changedAt(), state.changedByUserId(), state.version(), state.published());
	}
}
