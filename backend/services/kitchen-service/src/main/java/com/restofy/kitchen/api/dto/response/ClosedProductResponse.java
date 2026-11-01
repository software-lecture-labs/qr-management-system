package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.application.contract.ClosedProduct;
import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/** A closed product as Menu reports it; {@code null} fields are explicit (Menu did not provide them). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClosedProductResponse(
		UUID productId,
		String productName,
		ProductAvailabilityStatus status,
		String reasonCode,
		String reasonText,
		Instant changedAt,
		UUID changedByUserId,
		long version,
		Boolean published) {

	public static ClosedProductResponse from(ClosedProduct product) {
		return new ClosedProductResponse(product.productId(), product.productName(), product.status(), product.reasonCode(),
				product.reasonText(), product.changedAt(), product.changedByUserId(), product.version(), product.published());
	}
}
