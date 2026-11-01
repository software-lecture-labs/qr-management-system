package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.util.Set;
import java.util.UUID;

/**
 * What Kitchen asks the Menu service for: the products of ONE restaurant (taken from the verified principal only)
 * that are in one of the given closed statuses. Zero-based {@code page}; {@code size} is bounded by the caller.
 */
public record ClosedProductsQuery(UUID restaurantId, Set<ProductAvailabilityStatus> statuses, int page, int size) {
}
