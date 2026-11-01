package com.restofy.waiter.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * "Mark this order as served", with the context the staff member saw: the table session the order belongs to
 * and the order version. Order checks both together with the order's state, atomically. Restaurant and staff
 * member are never part of the request; they come from the verified identity.
 */
public record ServeOrderRequest(@NotNull UUID tableSessionId, @NotNull @PositiveOrZero Long expectedVersion) {
}
