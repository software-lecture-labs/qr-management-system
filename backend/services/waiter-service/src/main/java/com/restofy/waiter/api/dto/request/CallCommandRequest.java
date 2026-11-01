package com.restofy.waiter.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Body of claim and resolve: the version of the call the staff member was looking at (optimistic locking). */
public record CallCommandRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
