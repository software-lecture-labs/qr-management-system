package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** A paid extra ingredient. Only the catalog option id and a quantity are accepted; never a price or name. */
public record ExtraSelectionRequest(
        @NotNull UUID ingredientId,
        @NotNull @Min(1) Integer quantity) {
}
