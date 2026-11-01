package com.restofy.qr.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Verified staff intent, not payment evidence or authority to override Order. */
public final class StaffClosure {
    private StaffClosure() { }

    public enum Origin { STAFF, WAITER_SERVICE }

    public record Request(UUID commandId, UUID restaurantId, UUID tableId, UUID tableSessionId,
                          long expectedVersion, UUID requestedByUserId, String reason, Instant requestedAt) {
        public Request {
            Objects.requireNonNull(commandId); Objects.requireNonNull(restaurantId);
            Objects.requireNonNull(tableId); Objects.requireNonNull(tableSessionId);
            Objects.requireNonNull(requestedByUserId); Objects.requireNonNull(requestedAt);
            requestedAt = IdleWindow.storedTime(requestedAt);
            reason = reason == null || reason.isBlank() ? null : reason.trim();
            if (expectedVersion < 0 || (reason != null && (reason.length() > 300
                    || reason.codePoints().anyMatch(Character::isISOControl)))) {
                throw new IllegalArgumentException("Invalid staff closure request.");
            }
        }
    }

    public record Initiator(Request request, Origin origin) {
        public Initiator { Objects.requireNonNull(request); Objects.requireNonNull(origin); }

        public String canonical() {
            String reason = request.reason() == null ? "-" : Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(request.reason().getBytes(StandardCharsets.UTF_8));
            return origin + "|" + request.commandId() + "|" + request.restaurantId() + "|" + request.tableId()
                    + "|" + request.tableSessionId() + "|" + request.expectedVersion() + "|"
                    + request.requestedByUserId() + "|" + reason + "|" + request.requestedAt();
        }
    }
}
