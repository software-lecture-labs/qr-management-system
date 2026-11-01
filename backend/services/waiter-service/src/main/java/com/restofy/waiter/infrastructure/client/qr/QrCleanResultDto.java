package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.enums.TableOperationalStatus;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * DRAFT QR contract v0.4: QR's record of a cleaning command, as the answer of the command itself
 * ({@code APPLIED}, or {@code DUPLICATE} for the same command id again) and as the answer of the status query
 * ({@code APPLIED} or {@code REJECTED}). An applied cleaning carries QR's table snapshot (its version). The
 * command's own answer must show the table as available to the next customer ({@code AVAILABLE}, active,
 * without a live session; the client checks that), whereas a later status answer may find the table in use
 * again, which {@link #tableReady()} reports as false instead of refusing the answer.
 */
public record QrCleanResultDto(UUID commandId, Status status, String rejectionCode, QrTableDto table) {

    public enum Status { APPLIED, DUPLICATE, REJECTED }

    public QrCleanResultDto {
        required(commandId, "commandId");
        required(status, "status");
        if (status == Status.REJECTED) {
            requiredText(rejectionCode, "rejectionCode");
        } else {
            required(table, "table");
        }
    }

    /** QR's snapshot shows a table that is available, active and without a live session. */
    public boolean tableReady() {
        return table != null && table.operationalStatus() == TableOperationalStatus.AVAILABLE && table.active()
                && table.currentSession() == null;
    }
}
