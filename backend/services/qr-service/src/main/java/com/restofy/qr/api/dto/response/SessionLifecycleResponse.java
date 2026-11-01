package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.enums.*;
import java.time.Instant;
import java.util.UUID;

public record SessionLifecycleResponse(UUID sessionId, UUID tableId, TableSessionStatus status, long version,
                                       Instant closingAt, Instant closedAt, UUID closedByUserId,
                                       TableOperationalStatus tableStatus, long tableVersion) {
    public static SessionLifecycleResponse from(Result result) {
        return new SessionLifecycleResponse(result.sessionId(), result.tableId(), result.status(), result.version(),
                result.closingAt(), result.closedAt(), result.closedByUserId(), result.tableStatus(), result.tableVersion());
    }
}
