package com.restofy.order.api.dto.request;

import com.restofy.order.domain.closure.ClosureEffect;
import com.restofy.order.domain.enums.ClosureConfirmation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

/**
 * The QR Service reports the result of a prepared operation: {@code CLOSED} (it applied the change; {@code receipt} is
 * its local result) or {@code NOT_CLOSED} (it did not, which aborts). The receipt is optional but, when sent, its
 * {@code effect} must be the one that was permitted. The {@code commandDigest} of the exact command is required: it
 * must be the one the operation was prepared with, and it is what lets the QR Service check that an answer, even the
 * "nothing to abort" one for an operation this service never saw, belongs to its own command.
 */
public record ConfirmClosureRequest(@NotNull UUID restaurantId, @NotNull ClosureConfirmation outcome,
                                    @Valid Receipt receipt,
                                    @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String commandDigest) {

    /** What the QR Service did locally: the effect, the versions it ended on, when, and how many guests expired. */
    public record Receipt(@NotNull ClosureEffect effect, @PositiveOrZero long sessionVersion,
                          @PositiveOrZero long tableVersion, @NotNull Instant completedAt,
                          @PositiveOrZero int expiredGuests) {
    }
}
