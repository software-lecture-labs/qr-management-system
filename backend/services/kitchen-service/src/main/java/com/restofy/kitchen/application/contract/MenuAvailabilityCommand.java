package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.util.UUID;

/**
 * What Kitchen asks the Menu service to do (proposed contract, not integrated). Restaurant and user come from the
 * verified principal only; {@code commandId} is the client's Idempotency-Key and must make Menu idempotent.
 * {@code reasonCode} and {@code reasonText} are optional: {@code null} means "no reason given" and Menu must accept it
 * (not verified against a real Menu service yet).
 */
public record MenuAvailabilityCommand(
		UUID commandId,
		UUID restaurantId,
		UUID productId,
		ProductAvailabilityStatus targetStatus,
		String reasonCode,
		String reasonText,
		UUID requestedByUserId) {
}
