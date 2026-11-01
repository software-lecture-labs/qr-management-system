package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.enums.TableCommandType;

import java.time.Instant;
import java.util.UUID;

/**
 * A close or cleaning command of this table that has no final answer of QR yet. It is a warning, not a table
 * fact: the table may or may not have been changed already, and the command can be queried by its id.
 */
public record PendingCommandResponse(UUID commandId, TableCommandType type, TableCommandStatus status, Instant requestedAt) {
}
