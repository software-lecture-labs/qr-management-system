package com.restofy.qr.api.dto.response;

import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A command receipt. CLOSED requires that no active guest of the target session remains. */
public record StaffClosureResponse(UUID commandId, State state, UUID tableId, UUID tableSessionId, Instant closedAt,
                                    GuestSessions guestSessions, Table table, String rejectionCode, List<String> blockers) {
    public enum State { CLOSED, CLOSING, REJECTED }
    public StaffClosureResponse { blockers = List.copyOf(blockers); }
    public record GuestSessions(int total, int ended) { }
    public record Table(UUID id, UUID restaurantId, String tableNumber, String displayName, Integer capacity,
                        TableOperationalStatus operationalStatus, boolean active, long version, LiveSession currentSession) { }
    public record LiveSession(UUID id, TableSessionStatus status, Instant openedAt, Instant lastActivityAt,
                              Integer scanCount, long version) { }
}
