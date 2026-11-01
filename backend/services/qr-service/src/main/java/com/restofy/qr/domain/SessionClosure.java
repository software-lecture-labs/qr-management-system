package com.restofy.qr.domain;

import com.restofy.qr.domain.enums.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Verified staff context for normal closure/cleaning; SessionTransition preserves the durable actor and operation. */
public final class SessionClosure {
    private SessionClosure() { }
    public record Staff(UUID restaurantId, UUID userId) {
        public Staff { Objects.requireNonNull(restaurantId); Objects.requireNonNull(userId); }
    }
    public record Result(UUID sessionId, UUID tableId, TableSessionStatus status, long version,
                         Instant closingAt, Instant closedAt, UUID closedByUserId,
                         TableOperationalStatus tableStatus, long tableVersion) { }
}
