package com.restofy.waiter.infrastructure.client.order;

import java.time.Instant;
import java.util.UUID;

/**
 * DRAFT Order contract v0: the command "mark this order as served". {@code commandId} is stable for the whole
 * life of the command and is also sent as the Idempotency-Key, so Order can tell a resend from a new command.
 * {@code tableSessionId} and {@code expectedVersion} are the context the staff member saw; Order must check
 * both, together with the order's state, atomically. {@code requestedByUserId} comes from the verified staff
 * identity of Waiter, never from the browser.
 */
public record ServeCommandRequestDto(UUID commandId, UUID restaurantId, UUID tableSessionId, long expectedVersion,
                                     UUID requestedByUserId, Instant requestedAt) {
}
