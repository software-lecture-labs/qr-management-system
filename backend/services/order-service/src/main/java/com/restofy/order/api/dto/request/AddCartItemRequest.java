package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Adds a product to the caller's cart. Prices, names and tax are never accepted from the client.
 * {@code expectedCartVersion} optionally guards against acting on a stale cart.
 */
public record AddCartItemRequest(
        @NotNull UUID productId,
        @NotNull @Min(1) Integer quantity,
        @Size(max = InputLimits.MAX_SELECTIONS) List<@NotNull UUID> removedIngredientIds,
        @Size(max = InputLimits.MAX_SELECTIONS) List<@NotNull @Valid ExtraSelectionRequest> extras,
        @Size(max = InputLimits.MAX_TEXT_LENGTH) String note,
        @Min(0) Long expectedCartVersion) {
}
