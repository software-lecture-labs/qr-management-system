package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One closed product exactly as the Menu service reports it. {@code productName} is the catalogue's own text
 * ({@code null} if Menu does not provide it); {@code changedAt} / {@code changedByUserId} are Menu's record of the
 * closing; {@code published} is the catalogue flag, separate from availability ({@code null} = Menu did not say).
 */
public record ClosedProduct(
		UUID productId,
		String productName,
		ProductAvailabilityStatus status,
		String reasonCode,
		String reasonText,
		Instant changedAt,
		UUID changedByUserId,
		long version,
		Boolean published) {
}
