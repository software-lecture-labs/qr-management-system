package com.restofy.waiter.domain.exception;

import java.util.UUID;

/**
 * The serve command may have been applied by Order, but no usable answer arrived (timeout, connection lost,
 * server error, unreadable answer). Waiter neither reports success nor failure; the caller reconciles the
 * outcome through the command id.
 */
public class ServeResultUnknownException extends RuntimeException {

    private final UUID commandId;
    private final DependencyFailure failure;

    public ServeResultUnknownException(UUID commandId, DependencyFailure failure) {
        super("The result of the serve command is unknown. Do not assume the order was or was not served; "
                + "query the command or repeat the same request with the same Idempotency-Key.");
        this.commandId = commandId;
        this.failure = failure;
    }

    public UUID commandId() {
        return commandId;
    }

    public DependencyFailure failure() {
        return failure;
    }
}
