package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.TableOperationalStatus;

import java.util.UUID;

/**
 * A table of the caller's restaurant. {@code operationalStatus} and {@code session} come from QR;
 * {@code activeOrderCount} from Order; the call fields from the local call model. They are independent
 * facts: a table without orders is not necessarily empty, and an occupied table may have no orders yet.
 * {@code version} is QR's version of the table (the value a cleaning command quotes);
 * {@code readyForNewCustomer} is true only when QR reports the table as available, active and without a live
 * session; {@code pendingCommand} warns about a close or cleaning command without a final answer of QR.
 */
public record TableSummaryResponse(UUID id, String tableNumber, String displayName, Integer capacity,
                                   boolean active, TableOperationalStatus operationalStatus,
                                   TableSessionResponse session, int activeOrderCount, int openCallCount,
                                   boolean billRequested, long version, boolean readyForNewCustomer,
                                   PendingCommandResponse pendingCommand) {
}
