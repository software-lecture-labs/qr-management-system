package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Sets the absolute quantity of a cart line. Removing a line is a separate DELETE operation. */
public record UpdateCartItemQuantityRequest(
        @NotNull @Min(1) Integer quantity,
        @Min(0) Long expectedCartVersion) {
}
