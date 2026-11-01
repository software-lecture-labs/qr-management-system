package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A product's availability exactly as the Menu service confirmed it; {@code changedAt} is Menu's own time.
 * {@code published} is the catalogue's own flag, kept apart from availability: an AVAILABLE product that is not
 * published is still not on sale, and reopening a product never publishes it. {@code null} = Menu did not say.
 */
public record ProductAvailabilityState(
		UUID productId,
		ProductAvailabilityStatus status,
		String reasonCode,
		String reasonText,
		Instant changedAt,
		UUID changedByUserId,
		long version,
		Boolean published) {
}
