package com.restofy.qr.api.dto.request;

import com.restofy.qr.domain.StaffClosure;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

/** Matches Waiter's close-command payload. Claimed IDs are checked against verified request context. */
public record StaffCloseRequest(@NotNull UUID commandId, @NotNull UUID restaurantId, @NotNull UUID tableId,
                                @NotNull UUID tableSessionId, @NotNull @PositiveOrZero Long expectedVersion,
                                @NotNull UUID requestedByUserId, Object reason, @NotNull Instant requestedAt) {
    public StaffCloseRequest {
        if (reason != null && !(reason instanceof String)) { throw new IllegalArgumentException("Invalid reason type."); }
    }

    public StaffClosure.Request command() {
        return new StaffClosure.Request(commandId, restaurantId, tableId, tableSessionId, expectedVersion,
                requestedByUserId, (String) reason, requestedAt);
    }
}
