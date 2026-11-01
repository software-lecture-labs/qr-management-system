package com.restofy.waiter.domain.exception;

import com.restofy.waiter.domain.enums.TableCommandType;

import java.util.Locale;
import java.util.UUID;

/**
 * The table command may have been applied by QR, but no usable answer arrived (timeout, connection lost, server
 * error, unreadable or contradictory answer). Waiter neither reports success nor failure; the caller reconciles
 * the outcome through the command id.
 */
public class TableCommandUnknownException extends RuntimeException {

    private final TableCommandType type;
    private final UUID commandId;
    private final DependencyFailure failure;

    public TableCommandUnknownException(TableCommandType type, UUID commandId, DependencyFailure failure) {
        super("The result of the " + type.name().toLowerCase(Locale.ROOT) + " command is unknown. Do not "
                + "assume the table was or was not changed; query the command or repeat the same request with the "
                + "same Idempotency-Key.");
        this.type = type;
        this.commandId = commandId;
        this.failure = failure;
    }

    public TableCommandType type() {
        return type;
    }

    public UUID commandId() {
        return commandId;
    }

    public DependencyFailure failure() {
        return failure;
    }
}
