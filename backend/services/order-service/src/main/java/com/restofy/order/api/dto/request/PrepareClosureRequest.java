package com.restofy.order.api.dto.request;

import com.restofy.order.domain.closure.ClosurePurpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The QR Service asks the Order Service for a permit to change the lifecycle of a table session and, when it is given,
 * to keep orders out until the change is confirmed or aborted. {@code operationId} is the QR Service's durable id of
 * the command and {@code commandDigest} the digest of the exact command: asking again with the same id and digest
 * returns the same operation, the same id with another digest is a conflict. {@code restaurantId} names the tenant the
 * table session belongs to; the table session id is in the path.
 *
 * <p>{@code SETTLED} (close a settled session) needs nothing more. {@code IDLE} (expire an idle session) also needs
 * {@code requestedAt}, {@code mainIdleExpiresAt} and {@code guests} (each guest of the command with the time its own idle
 * window ends), because the Order Service decides which of them orders still protect.
 */
public record PrepareClosureRequest(@NotNull UUID restaurantId, @NotNull UUID operationId,
                                    @NotNull ClosurePurpose purpose,
                                    @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String commandDigest,
                                    Instant requestedAt, Instant mainIdleExpiresAt,
                                    @Size(max = 500) List<@Valid IdleGuest> guests) {

    /** A guest session of the command and when its idle window ends. */
    public record IdleGuest(@NotNull UUID guestSessionId, @NotNull Instant idleExpiresAt) {
    }

    /** The settled-close command, which needs no idle details. */
    public static PrepareClosureRequest settled(UUID restaurantId, UUID operationId, String commandDigest) {
        return new PrepareClosureRequest(restaurantId, operationId, ClosurePurpose.SETTLED, commandDigest, null, null,
                null);
    }
}
