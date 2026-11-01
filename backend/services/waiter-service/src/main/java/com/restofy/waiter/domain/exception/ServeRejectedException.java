package com.restofy.waiter.domain.exception;

import java.util.UUID;

/**
 * Order (or the trust boundary towards it) definitively did not apply the serve command. {@code status} is
 * the HTTP status Waiter answers with; {@code code} is a stable machine-readable reason.
 */
public class ServeRejectedException extends RuntimeException {

    private final int status;
    private final String code;
    private final UUID commandId;

    public ServeRejectedException(int status, String code, UUID commandId, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.commandId = commandId;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public UUID commandId() {
        return commandId;
    }
}
